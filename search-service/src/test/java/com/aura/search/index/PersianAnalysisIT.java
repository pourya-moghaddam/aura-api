package com.aura.search.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.indices.AnalyzeRequest;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.aura.search.config.SearchProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Persian analysis chain, against a real Elasticsearch.
 *
 * <p>This is the one place where a default Elasticsearch setup produces visibly bad results for
 * Persian, and every assertion below corresponds to a way a shopper's search silently returns
 * nothing. None of it can be tested without a real cluster: the analyzers are the cluster's, and
 * a mock would only ever confirm that the JSON was passed along.
 *
 * <p>The four foldings that matter, in the order a Persian speaker would notice them missing:
 * ک/ك and ی/ي (typed interchangeably depending on the keyboard), the zero-width non-joiner (the
 * same compound written two ways), and Persian digits.
 */
@Testcontainers
class PersianAnalysisIT {

    @Container
    static final ElasticsearchContainer ELASTICSEARCH = new ElasticsearchContainer(
        DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.15.3"))
        .withEnv("xpack.security.enabled", "false")
        .withEnv("discovery.type", "single-node")
        .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m");

    private static final String ALIAS = "products";

    private static ElasticsearchClient client;

    @BeforeAll
    static void createIndex() {
        URI uri = URI.create("http://" + ELASTICSEARCH.getHttpHostAddress());
        RestClient restClient = RestClient
            .builder(new HttpHost(uri.getHost(), uri.getPort(), "http"))
            .build();
        client = new ElasticsearchClient(
            new RestClientTransport(restClient, new JacksonJsonpMapper(new ObjectMapper())));

        SearchProperties properties = new SearchProperties(uri.toString(), null, null, ALIAS, true);
        new IndexBootstrapper(client, new IndexDefinition(), properties).createIndexIfMissing();
    }

    /** The tokens the indexing analyzer produces for a piece of text. */
    private List<String> analyze(String text) throws IOException {
        return client.indices()
            .analyze(AnalyzeRequest.of(a -> a.index(ALIAS).analyzer("aura_persian").text(text)))
            .tokens().stream()
            .map(token -> token.token())
            .toList();
    }

    @Test
    @DisplayName("the index exists behind the alias, not under its own name")
    void aliasPointsAtTheIndex() throws IOException {
        // Everything reads and writes through the alias so a mapping change is a reindex and an
        // atomic flip. If code ever addressed products_v1 directly, that stops being possible.
        assertThat(client.indices().existsAlias(a -> a.name(ALIAS)).value()).isTrue();
        assertThat(client.indices().getAlias(a -> a.name(ALIAS)).result())
            .containsOnlyKeys("products_v1");
    }

    @Test
    @DisplayName("Arabic and Persian kaf fold together")
    void kafVariants() throws IOException {
        // U+0643 (Arabic kaf) and U+06A9 (Persian keheh) look identical and are typed
        // interchangeably depending on the keyboard. Untreated, half of Iran cannot find a
        // product whose name was entered with the other one.
        assertThat(analyze("كتاب"))
            .isEqualTo(analyze("کتاب"));
    }

    @Test
    @DisplayName("Arabic and Persian yeh fold together")
    void yehVariants() throws IOException {
        // U+064A (Arabic yeh) and U+06CC (Farsi yeh), the same story.
        assertThat(analyze("صندلي"))
            .isEqualTo(analyze("صندلی"));
    }

    @Test
    @DisplayName("the zero-width non-joiner is removed, so one compound is one token")
    void zwnjIsNormalised() throws IOException {
        // "کتاب‌ها" (with ZWNJ) and "کتابها" (without) are the same word written two ways.
        // Elasticsearch's stock Persian analyzer maps the ZWNJ to a space, which splits the first
        // into two tokens and leaves them unable to match each other. Removing it instead makes
        // both one token, which is what a shopper expects.
        List<String> withZwnj = analyze("کتاب‌ها");
        List<String> without = analyze("کتابها");

        assertThat(withZwnj).hasSize(1);
        assertThat(withZwnj).isEqualTo(without);
    }

    @Test
    @DisplayName("Persian digits fold to ASCII, so ۴۲ matches 42")
    void persianDigits() throws IOException {
        assertThat(analyze("۴۲")).containsExactly("42");
    }

    @Test
    @DisplayName("Arabic-Indic digits fold too")
    void arabicDigits() throws IOException {
        assertThat(analyze("٤٢")).containsExactly("42");
    }

    @Test
    @DisplayName("Latin brand names are lowercased, so Nike matches nike")
    void mixedLatin() throws IOException {
        assertThat(analyze("NIKE")).containsExactly("nike");
    }

    @Test
    @DisplayName("Persian stop words are dropped")
    void stopWords() throws IOException {
        // "از" (from) carries no meaning in a product search and would otherwise match everything.
        assertThat(analyze("از کتاب"))
            .doesNotContain("از");
    }

    @Test
    @DisplayName("normalisation folds toward the Arabic letters, not the Persian ones")
    void normalisationDirection() throws IOException {
        // Worth stating plainly because it surprises: Lucene's persian_normalization maps keheh
        // (U+06A9) to kaf (U+0643) and Farsi yeh (U+06CC) to yeh (U+064A), so a stored token does
        // not look like what a Persian speaker typed. That is fine - queries go through the same
        // filter - but anyone reading the index by hand needs to expect it.
        assertThat(analyze("کتاب")).containsExactly("كتاب");
    }

    @Test
    @DisplayName("the edge-ngram analyzer produces prefixes for as-you-type search")
    void edgeNgrams() throws IOException {
        List<String> tokens = client.indices()
            .analyze(AnalyzeRequest.of(a -> a.index(ALIAS)
                .analyzer("aura_persian_edge")
                .text("کتاب")))
            .tokens().stream().map(t -> t.token()).toList();

        // Prefixes of the *normalised* token, since normalisation runs before the ngram filter.
        // Deriving the expectation rather than writing it out keeps this honest about which forms
        // actually land in the index.
        String normalised = analyze("کتاب").getFirst();

        // Two characters upwards, so a shopper sees results before finishing the word.
        assertThat(tokens).contains(
            normalised.substring(0, 2), normalised.substring(0, 3), normalised);
        assertThat(tokens).doesNotContain(normalised.substring(0, 1));
    }

    @Test
    @DisplayName("the ngram field is searched with the plain analyzer, not the ngram one")
    void ngramSearchAnalyzer() throws IOException {
        // If the query were also ngrammed, "کت" would match anything sharing any prefix and
        // relevance would collapse. The subfield indexes prefixes and searches whole terms.
        var mapping = client.indices().getMapping(m -> m.index(ALIAS)).result()
            .get("products_v1").mappings().properties().get("name").text()
            .fields().get("ngram").text();

        assertThat(mapping.analyzer()).isEqualTo("aura_persian_edge");
        assertThat(mapping.searchAnalyzer()).isEqualTo("aura_persian");
    }

    @Test
    @DisplayName("a document written one way is found by the other spelling")
    void roundTrip() throws IOException {
        // The whole point, end to end: indexed with ZWNJ and Arabic kaf, searched without either.
        client.index(i -> i
            .index(ALIAS)
            .id("1")
            .document(Map.of(
                "productId", 1,
                "name", "كتاب‌های ۴۲",
                "status", "ACTIVE"))
            .refresh(co.elastic.clients.elasticsearch._types.Refresh.True));

        SearchResponse<Map> found = client.search(s -> s
            .index(ALIAS)
            .query(q -> q.match(m -> m
                .field("name")
                .query("کتابهای 42"))), Map.class);

        assertThat(found.hits().hits())
            .as("different keyboard, no ZWNJ, ASCII digits - still the same product")
            .hasSize(1);
    }

    @Test
    @DisplayName("attribute values are indexed as keywords, so they can be aggregated")
    void attributesAreKeywords() throws IOException {
        // The facet sidebar aggregates on attributes.<slug>. A dynamically mapped text field would
        // make those aggregations either fail or return analyzed fragments.
        client.index(i -> i
            .index(ALIAS)
            .id("2")
            .document(Map.of(
                "productId", 2,
                "name", "کفش",
                "attributes", Map.of("material", List.of("Leather"))))
            .refresh(co.elastic.clients.elasticsearch._types.Refresh.True));

        var aggregated = client.search(s -> s
            .index(ALIAS)
            .size(0)
            .aggregations("material", a -> a.terms(t -> t.field("attributes.material"))), Map.class);

        assertThat(aggregated.aggregations().get("material").sterms().buckets().array())
            .extracting(bucket -> bucket.key().stringValue())
            .containsExactly("leather");
    }
}
