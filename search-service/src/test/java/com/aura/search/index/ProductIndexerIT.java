package com.aura.search.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.aura.common.events.ProductChangedEvent;
import com.aura.search.config.SearchProperties;
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

/**
 * External versioning, against a real Elasticsearch.
 *
 * <p>This is the property the plan asks for and the one that cannot be tested any other way: the
 * refusal happens inside Elasticsearch, and a mocked client would happily accept whatever it was
 * given in whatever order.
 *
 * <p>The failure it prevents is quiet. Kafka orders within a partition, so ordinary delivery is
 * fine; it is <em>re</em>delivery — a consumer that failed and retried, a partition that moved —
 * that can present yesterday's document after today's. Without versioning the index would then
 * hold a price or a stock level that catalog corrected hours ago, and nothing would look broken.
 */
@Testcontainers
class ProductIndexerIT {

    @Container
    static final ElasticsearchContainer ELASTICSEARCH = new ElasticsearchContainer(
        DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.15.3"))
        .withEnv("xpack.security.enabled", "false")
        .withEnv("discovery.type", "single-node")
        .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m");

    private static final long PRODUCT = 42L;

    private static ElasticsearchClient client;
    private static SearchProperties properties;
    private static ProductIndexer indexer;

    @BeforeAll
    static void startUp() {
        URI uri = URI.create("http://" + ELASTICSEARCH.getHttpHostAddress());
        client = new ElasticsearchClient(new RestClientTransport(
            RestClient.builder(new HttpHost(uri.getHost(), uri.getPort(), "http")).build(),
            new JacksonJsonpMapper(new ObjectMapper().findAndRegisterModules())));

        properties = new SearchProperties(uri.toString(), "products", true);
        new IndexBootstrapper(client, new IndexDefinition(), properties).createIndexIfMissing();
        indexer = new ProductIndexer(client, properties);
    }

    /**
     * A fresh index per test, not a delete-by-query.
     *
     * <p>Deleting documents leaves tombstones that remember the last external version, so the next
     * test indexing at a lower version is refused — correctly, and confusingly. Version history is
     * exactly what these tests manipulate, so it has to start empty each time.
     */
    @BeforeEach
    void freshIndex() throws IOException {
        if (client.indices().exists(e -> e.index("products_v1")).value()) {
            client.indices().delete(d -> d.index("products_v1"));
        }
        new IndexBootstrapper(client, new IndexDefinition(), properties).createIndexIfMissing();
    }

    private ProductChangedEvent event(String name, long version, boolean deleted) {
        return new ProductChangedEvent(
            UUID.randomUUID(), Instant.now(), PRODUCT, 9L, 3L,
            List.of(1L, 3L), List.of("پوشاک", "پیراهن"),
            name, "shirt", "توضیحات", "ACTIVE",
            500_000L, 900_000L, 7, Map.of("material", List.of("Cotton")),
            List.of("Navy"), List.of("L"), null,
            Instant.parse("2026-01-01T00:00:00Z"), version, deleted);
    }

    private void refresh() throws IOException {
        client.indices().refresh(r -> r.index("products"));
    }

    private String indexedName() throws IOException {
        refresh();
        var response = client.get(g -> g.index("products").id(String.valueOf(PRODUCT)), Map.class);
        return response.found() ? (String) response.source().get("name") : null;
    }

    @Test
    @DisplayName("an event puts the product in the index")
    void indexes() throws IOException {
        assertThat(indexer.apply(event("پیراهن", 1_000L, false))).isTrue();

        assertThat(indexedName()).isEqualTo("پیراهن");
    }

    @Test
    @DisplayName("a newer event replaces the document")
    void newerWins() throws IOException {
        indexer.apply(event("قدیمی", 1_000L, false));

        assertThat(indexer.apply(event("جدید", 2_000L, false))).isTrue();
        assertThat(indexedName()).isEqualTo("جدید");
    }

    @Test
    @DisplayName("an older event redelivered late does not clobber the newer document")
    void staleEventIsRefused() throws IOException {
        // The whole reason for external versioning. Without it this is a silent regression: the
        // index goes back to a name, price or stock level catalog corrected hours ago, and nothing
        // anywhere reports a problem.
        indexer.apply(event("جدید", 2_000L, false));

        assertThat(indexer.apply(event("قدیمی", 1_000L, false)))
            .as("the indexer should report that it changed nothing")
            .isFalse();
        assertThat(indexedName()).isEqualTo("جدید");
    }

    @Test
    @DisplayName("the same event delivered twice is a no-op")
    void duplicateIsIdempotent() throws IOException {
        // At-least-once delivery makes this ordinary rather than exceptional. Versioning gives
        // idempotence for nothing - no dedup table, nothing to keep tidy.
        ProductChangedEvent event = event("پیراهن", 1_000L, false);

        assertThat(indexer.apply(event)).isTrue();
        assertThat(indexer.apply(event)).isFalse();
        assertThat(indexedName()).isEqualTo("پیراهن");
    }

    @Test
    @DisplayName("a deleted event removes the document")
    void deletes() throws IOException {
        indexer.apply(event("پیراهن", 1_000L, false));

        assertThat(indexer.apply(event("پیراهن", 2_000L, true))).isTrue();
        assertThat(indexedName()).isNull();
    }

    @Test
    @DisplayName("a stale delete does not remove a newer document")
    void staleDeleteIsRefused() throws IOException {
        // A withdrawal followed by a republish, redelivered out of order. Taking the delete would
        // remove a product that is on sale, and only a shopper would notice.
        indexer.apply(event("زنده", 2_000L, false));

        assertThat(indexer.apply(event("پیراهن", 1_000L, true))).isFalse();
        assertThat(indexedName()).isEqualTo("زنده");
    }

    @Test
    @DisplayName("deleting something that was never indexed is not an error")
    void deleteOfAbsentIsFine() {
        // The desired state is "absent", and it is absent. Throwing would send a perfectly
        // correct message round the retry loop and into the dead-letter topic.
        assertThat(indexer.apply(event("پیراهن", 1_000L, true))).isFalse();
    }

    @Test
    @DisplayName("a product withdrawn and republished ends up present")
    void withdrawThenRepublish() throws IOException {
        indexer.apply(event("پیراهن", 1_000L, false));
        indexer.apply(event("پیراهن", 2_000L, true));

        assertThat(indexer.apply(event("برگشت", 3_000L, false))).isTrue();
        assertThat(indexedName()).isEqualTo("برگشت");
    }

    @Test
    @DisplayName("the indexed document is searchable through the Persian chain")
    void searchableAfterIndexing() throws IOException {
        // Ties this task to the last one: the analysis chain applies to what the indexer writes,
        // not only to hand-written documents. Indexed with the Arabic kaf and a ZWNJ, searched
        // with the Persian keheh and none.
        //
        // The two must be the same *word* - کتاب‌ها and کتابها are one word spelled two ways, but
        // کتاب‌های is a different, inflected one. The analyzer folds spelling, not morphology;
        // tolerating inflection is what the fuzzy and edge-ngram query buys, and that is the next
        // task rather than this one.
        indexer.apply(event("كتاب‌ها خوب", 1_000L, false));
        client.indices().refresh(r -> r.index("products"));

        var hits = client.search(s -> s
            .index("products")
            .query(q -> q.match(m -> m.field("name").query("کتابها"))), Map.class);

        assertThat(hits.hits().hits()).hasSize(1);
    }

    @Test
    @DisplayName("the category names catalog now sends are searchable")
    void categoryNamesAreSearchable() throws IOException {
        // Added to the event for this: the plan wants a query to match the category a product sits
        // in, and the event previously carried ids only, which no amount of text matching helps.
        indexer.apply(event("محصول", 1_000L, false));
        client.indices().refresh(r -> r.index("products"));

        var hits = client.search(s -> s
            .index("products")
            .query(q -> q.match(m -> m.field("categoryNames").query("پیراهن"))), Map.class);

        assertThat(hits.hits().hits()).hasSize(1);
    }

    @Test
    @DisplayName("the ancestor ids are indexed, so a category subtree is one term filter")
    void categoryPathIsFilterable() throws IOException {
        indexer.apply(event("محصول", 1_000L, false));
        client.indices().refresh(r -> r.index("products"));

        var hits = client.search(s -> s
            .index("products")
            // 1 is the root; the product is filed two levels down. Browsing a parent category has
            // to find it without this service knowing the shape of the tree.
            .query(q -> q.term(t -> t.field("categoryPath").value(1L))), Map.class);

        assertThat(hits.hits().hits()).hasSize(1);
    }

    @Test
    @DisplayName("the timestamp survives the round trip as a date, not a decimal")
    void updatedAtIsADate() throws IOException {
        // Jackson's default for an Instant is a decimal epoch, which Elasticsearch rejects for a
        // date field. Pinned on the record so a global mapper setting cannot quietly break it.
        indexer.apply(event("محصول", 1_000L, false));
        refresh();

        var source = client.get(g -> g.index("products").id(String.valueOf(PRODUCT)), Map.class)
            .source();

        assertThat((String) source.get("updatedAt")).endsWith("Z");
    }

    @Test
    @DisplayName("stock is stored as a flag as well as a count")
    void inStockIsIndexed() throws IOException {
        indexer.apply(event("محصول", 1_000L, false));
        refresh();

        var hits = client.search(s -> s
            .index("products")
            .query(q -> q.term(t -> t.field("inStock").value(true))), Map.class);

        assertThat(hits.hits().hits()).hasSize(1);
    }
}
