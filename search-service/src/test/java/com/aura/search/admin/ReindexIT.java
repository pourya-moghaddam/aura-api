package com.aura.search.admin;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.aura.common.events.ProductChangedEvent;
import com.aura.common.web.error.BusinessRuleException;
import com.aura.search.config.SearchProperties;
import com.aura.search.index.IndexBootstrapper;
import com.aura.search.index.IndexDefinition;
import com.aura.search.index.ProductIndexer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Rebuilding the index while it is being written to.
 *
 * <p>The interesting test is {@link #documentsWrittenDuringTheCopyAreNotLost}: everything the live
 * consumer writes while the first pass runs goes to the old index, and without a second pass those
 * documents are silently missing from the new one. Silently is the word — search keeps working,
 * returns slightly wrong results, and nothing anywhere reports a problem.
 */
@Testcontainers
class ReindexIT {

    @Container
    static final ElasticsearchContainer ELASTICSEARCH = new ElasticsearchContainer(
        DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.15.3"))
        .withEnv("xpack.security.enabled", "false")
        .withEnv("discovery.type", "single-node")
        .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m");

    private static ElasticsearchClient client;
    private static SearchProperties properties;
    private static ProductIndexer indexer;
    private static ReindexService reindexService;

    @BeforeAll
    static void startUp() {
        URI uri = URI.create("http://" + ELASTICSEARCH.getHttpHostAddress());
        client = new ElasticsearchClient(new RestClientTransport(
            RestClient.builder(new HttpHost(uri.getHost(), uri.getPort(), "http")).build(),
            new JacksonJsonpMapper(new ObjectMapper().findAndRegisterModules())));

        properties = new SearchProperties(uri.toString(), "products", true);
        indexer = new ProductIndexer(client, properties);
        reindexService = new ReindexService(client, new IndexDefinition(), properties);
    }

    @BeforeEach
    void freshCluster() throws IOException {
        for (String index : client.indices().get(g -> g.index("products*")).result().keySet()) {
            client.indices().delete(d -> d.index(index));
        }
        new IndexBootstrapper(client, new IndexDefinition(), properties).createIndexIfMissing();
    }

    private ProductChangedEvent product(long id, String name, long version) {
        return new ProductChangedEvent(
            UUID.randomUUID(), Instant.now(), id, 9L, 3L, List.of(1L, 3L), List.of("پوشاک"),
            name, "p" + id, "توضیحات", "ACTIVE", 1_000L, 1_000L, 5,
            Map.of("material", List.of("Cotton")), List.of("Navy"), List.of("L"), null,
            Instant.parse("2026-01-01T00:00:00Z"), version, false);
    }

    private String aliasIndex() throws IOException {
        return client.indices().getAlias(a -> a.name("products")).result().keySet()
            .iterator().next();
    }

    private long countThroughAlias() throws IOException {
        client.indices().refresh(r -> r.index("products"));
        return client.count(c -> c.index("products")).count();
    }

    private String nameThroughAlias(long productId) throws IOException {
        client.indices().refresh(r -> r.index("products"));
        var response = client.get(g -> g.index("products").id(String.valueOf(productId)), Map.class);
        return response.found() ? (String) response.source().get("name") : null;
    }

    @Test
    @DisplayName("a rebuild copies every document and moves the alias")
    void rebuildsAndFlips() throws IOException {
        indexer.apply(product(1, "یک", 1_000L));
        indexer.apply(product(2, "دو", 1_000L));

        ReindexService.ReindexResult result = reindexService.rebuild();

        assertThat(result.from()).isEqualTo("products_v1");
        assertThat(result.to()).isEqualTo("products_v2");
        assertThat(result.copied()).isEqualTo(2);
        assertThat(aliasIndex()).isEqualTo("products_v2");
        assertThat(countThroughAlias()).isEqualTo(2);
    }

    @Test
    @DisplayName("documents written while the copy runs are not lost")
    void documentsWrittenDuringTheCopyAreNotLost() throws IOException {
        // The consumer does not stop for a reindex. On a real catalogue the first pass takes
        // minutes, and everything written during it lands in the old index - so without the second
        // pass those products vanish from search with nothing reporting it.
        indexer.apply(product(1, "قدیمی", 1_000L));

        // Stand in for the window: write straight to the old index, exactly as the live consumer
        // would have while the first pass was running.
        client.index(i -> i.index("products_v1").id("2")
            .document(Map.of("productId", 2, "name", "حین کپی", "status", "ACTIVE"))
            .versionType(co.elastic.clients.elasticsearch._types.VersionType.External)
            .version(1_000L));
        client.indices().refresh(r -> r.index("products_v1"));

        ReindexService.ReindexResult result = reindexService.rebuild();

        assertThat(countThroughAlias())
            .as("both the original and the one written during the window")
            .isEqualTo(2);
        assertThat(nameThroughAlias(2)).isEqualTo("حین کپی");
        assertThat(result.copied() + result.caughtUp()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("the catch-up pass cannot overwrite something newer written after the flip")
    void catchUpDoesNotClobberNewerDocuments() throws IOException {
        // External versioning is what makes a second pass safe rather than reckless. Without it,
        // copying again would undo every edit that landed between the flip and the catch-up.
        indexer.apply(product(1, "نسخه قدیمی", 1_000L));

        ReindexService.ReindexResult first = reindexService.rebuild();
        assertThat(first.to()).isEqualTo("products_v2");

        // A newer edit arrives after the flip, through the alias, and must survive a further copy.
        indexer.apply(product(1, "نسخه جدید", 5_000L));

        // Copy again from the stale old index. The newer document has to win.
        client.reindex(r -> r
            .source(s -> s.index("products_v1"))
            .dest(d -> d.index("products_v2")
                .versionType(co.elastic.clients.elasticsearch._types.VersionType.External))
            .conflicts(co.elastic.clients.elasticsearch._types.Conflicts.Proceed)
            .refresh(true)
            .waitForCompletion(true));

        assertThat(nameThroughAlias(1)).isEqualTo("نسخه جدید");
    }

    @Test
    @DisplayName("external versions survive the rebuild, so later events are still judged correctly")
    void versionsAreCarriedAcross() throws IOException {
        indexer.apply(product(1, "اصلی", 5_000L));

        reindexService.rebuild();

        // A stale redelivery after the rebuild must still be refused. If the copy had reset
        // versions, every old event in the retry queue would be applied again on top of new data.
        assertThat(indexer.apply(product(1, "قدیمی", 1_000L))).isFalse();
        assertThat(nameThroughAlias(1)).isEqualTo("اصلی");
    }

    @Test
    @DisplayName("the old index is kept, so a bad mapping can be rolled back")
    void oldIndexSurvives() throws IOException {
        indexer.apply(product(1, "یک", 1_000L));

        reindexService.rebuild();

        assertThat(client.indices().exists(e -> e.index("products_v1")).value())
            .as("the one thing worse than a bad mapping is one with nothing to roll back to")
            .isTrue();
    }

    @Test
    @DisplayName("rebuilding twice keeps counting up")
    void generationsIncrement() throws IOException {
        indexer.apply(product(1, "یک", 1_000L));

        assertThat(reindexService.rebuild().to()).isEqualTo("products_v2");
        assertThat(reindexService.rebuild().to()).isEqualTo("products_v3");
        assertThat(aliasIndex()).isEqualTo("products_v3");
    }

    @Test
    @DisplayName("the new index gets the current analysis chain, not a copy of the old one")
    void newIndexUsesTheCurrentDefinition() throws IOException {
        // The whole reason a reindex exists: analysis cannot be changed on an open index.
        reindexService.rebuild();

        var analyzers = client.indices().getSettings(s -> s.index("products_v2")).result()
            .get("products_v2").settings().index().analysis().analyzer();

        assertThat(analyzers).containsKeys("aura_persian", "aura_persian_edge");
    }

    @Test
    @DisplayName("search keeps working throughout")
    void searchableAfterTheFlip() throws IOException {
        indexer.apply(product(1, "كتاب‌ها", 1_000L));

        reindexService.rebuild();

        // Through the alias, with the Persian chain still folding the spelling.
        var hits = client.search(s -> s
            .index("products")
            .query(q -> q.match(m -> m.field("name").query("کتابها"))), Map.class);

        assertThat(hits.hits().hits()).hasSize(1);
    }

    @Test
    @DisplayName("a half-finished previous attempt is refused rather than mixed into")
    void refusesAnExistingTarget() throws IOException {
        indexer.apply(product(1, "یک", 1_000L));
        client.indices().create(c -> c.index("products_v2"));

        assertThatThrownBy(() -> reindexService.rebuild())
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("already exists");
    }

    @Test
    @DisplayName("an alias pointing at two indices is refused rather than guessed at")
    void refusesAnAmbiguousAlias() throws IOException {
        // An interrupted reindex leaves this. Picking one to copy from could drop half the shop.
        client.indices().create(c -> c.index("products_v9"));
        client.indices().updateAliases(u -> u
            .actions(a -> a.add(add -> add.index("products_v9").alias("products"))));

        assertThatThrownBy(() -> reindexService.rebuild())
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("more than one index");
    }
}
