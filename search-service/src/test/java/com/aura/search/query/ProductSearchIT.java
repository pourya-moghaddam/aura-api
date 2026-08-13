package com.aura.search.query;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.aura.common.events.ProductChangedEvent;
import com.aura.search.config.SearchProperties;
import com.aura.search.index.IndexBootstrapper;
import com.aura.search.index.IndexDefinition;
import com.aura.search.index.ProductIndexer;
import com.aura.search.query.dto.SearchQuery;
import com.aura.search.query.dto.SearchResults;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 9, against real Persian data.
 *
 * <p>The requirement is two sentences — "words can be in any part of the name" and "similar, not
 * exact" — and neither can be checked without an actual analyzer and an actual scorer. A unit test
 * can assert that the fuzziness parameter is set; only this can assert that a shopper who mistypes
 * still finds the bag.
 */
@Testcontainers
class ProductSearchIT {

    @Container
    static final ElasticsearchContainer ELASTICSEARCH = new ElasticsearchContainer(
        DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.15.3"))
        .withEnv("xpack.security.enabled", "false")
        .withEnv("discovery.type", "single-node")
        .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m");

    private static ElasticsearchClient client;
    private static ProductSearchService searchService;

    @BeforeAll
    static void indexACatalogue() throws IOException {
        URI uri = URI.create("http://" + ELASTICSEARCH.getHttpHostAddress());
        client = new ElasticsearchClient(new RestClientTransport(
            RestClient.builder(new HttpHost(uri.getHost(), uri.getPort(), "http")).build(),
            new JacksonJsonpMapper(new ObjectMapper().findAndRegisterModules())));

        SearchProperties properties = new SearchProperties(uri.toString(), "products", true);
        new IndexBootstrapper(client, new IndexDefinition(), properties).createIndexIfMissing();

        ProductIndexer indexer = new ProductIndexer(client, properties);
        searchService = new ProductSearchService(client, properties, new ProductQueryBuilder());

        // A small shop, in Persian, with the awkward cases on purpose.
        indexer.apply(product(1, "کیف چرم زنانه", "کیف دستی از چرم طبیعی",
            List.of("کیف"), 800_000L, 900_000L, "ACTIVE", "2026-01-01T00:00:00Z"));
        indexer.apply(product(2, "کفش ورزشی مردانه", "کفش دویدن سبک",
            List.of("کفش"), 1_200_000L, 1_500_000L, "ACTIVE", "2026-02-01T00:00:00Z"));
        indexer.apply(product(3, "پیراهن نخی آبی", "پیراهن مردانه از جنس نخ",
            List.of("پوشاک", "پیراهن"), 400_000L, 450_000L, "ACTIVE", "2026-03-01T00:00:00Z"));
        indexer.apply(product(4, "کوله‌پشتی کوهنوردی", "کوله بزرگ برای سفر",
            List.of("کیف"), 2_000_000L, 2_000_000L, "ACTIVE", "2026-04-01T00:00:00Z"));
        indexer.apply(product(5, "Nike Air Max", "کفش اسپرت نایک",
            List.of("کفش"), 3_000_000L, 3_000_000L, "ACTIVE", "2026-05-01T00:00:00Z"));
        // Withdrawn: indexed as a document but must never appear in results.
        indexer.apply(product(6, "کیف قدیمی", "دیگر فروخته نمی‌شود",
            List.of("کیف"), 100_000L, 100_000L, "ARCHIVED", "2026-06-01T00:00:00Z"));

        client.indices().refresh(r -> r.index("products"));
    }

    private static ProductChangedEvent product(long id, String name, String description,
                                               List<String> categoryNames, long minPrice,
                                               long maxPrice, String status, String createdAt) {
        return new ProductChangedEvent(
            UUID.randomUUID(), Instant.now(), id, 9L, 3L, List.of(1L, 3L), categoryNames,
            name, "p" + id, description, status, minPrice, maxPrice, 5,
            Map.of("material", List.of("Leather")), List.of("Navy"), List.of("L"),
            null, Instant.parse(createdAt), 1_000L + id, false);
    }

    private SearchResults search(String text) {
        return searchService.search(new SearchQuery(text, null, null, null));
    }

    private List<Long> idsOf(SearchResults results) {
        return results.hits().stream().map(h -> h.productId()).toList();
    }

    @Test
    @DisplayName("an exact name is found and ranked first")
    void exactMatchWins() {
        assertThat(idsOf(search("کیف چرم زنانه")).getFirst()).isEqualTo(1L);
    }

    @Test
    @DisplayName("a word from the middle of the name finds the product")
    void wordFromTheMiddle() {
        // "words can be in any part of the name" - the first half of requirement 9.
        assertThat(idsOf(search("چرم"))).contains(1L);
    }

    @Test
    @DisplayName("a word from the description finds it too")
    void wordFromTheDescription() {
        assertThat(idsOf(search("دویدن"))).contains(2L);
    }

    @Test
    @DisplayName("two words split between name and description both count")
    void wordsAcrossFields() {
        // What cross_fields buys: "پیراهن" is in the name, "نخ" in the description.
        assertThat(idsOf(search("پیراهن نخ"))).contains(3L);
    }

    @Test
    @DisplayName("a mistyped word still finds the product")
    void typoTolerance() {
        // "similar, not exact" - the second half of requirement 9. Every query below is genuinely
        // wrong by one character, which is the only way this assertion means anything.
        assertThat(idsOf(search("کیف چزم")))           // چرم with ز for ر
            .as("one substituted letter")
            .contains(1L);
        assertThat(idsOf(search("کفش ورزسی")))        // ورزشی with س for ش
            .as("another substitution, in the second word")
            .contains(2L);
        assertThat(idsOf(search("پیراهن نخی ابی")))   // آبی without the madda, as people type it
            .as("a missing diacritic")
            .contains(3L);
    }

    @Test
    @DisplayName("a mistyped Latin brand is found too")
    void latinTypo() {
        assertThat(idsOf(search("nkie"))).contains(5L);
    }

    @Test
    @DisplayName("the first letter is not treated as a typo")
    void firstLetterIsFixed() {
        // prefix_length: 1. Without it "کیف" (bag) and "کفش" (shoe) are one edit apart and match
        // each other, and a shopper searching for bags is shown shoes.
        assertThat(idsOf(search("کیف")))
            .contains(1L)
            .doesNotContain(2L);
    }

    @Test
    @DisplayName("a different keyboard finds the same product")
    void arabicKeyboard() {
        // كيف with the Arabic kaf and yeh rather than the Persian ones.
        assertThat(idsOf(search("كيف چرم"))).contains(1L);
    }

    @Test
    @DisplayName("a Latin brand name is found regardless of case")
    void latinBrand() {
        assertThat(idsOf(search("nike"))).contains(5L);
        assertThat(idsOf(search("NIKE"))).contains(5L);
    }

    @Test
    @DisplayName("a partial word finds products as the shopper types")
    void asYouType() {
        // The edge-ngram field. "کول" is not a word; it is what someone has typed so far.
        assertThat(idsOf(search("کول"))).contains(4L);
    }

    @Test
    @DisplayName("a category name matches, so browsing by word works")
    void categoryName() {
        // The names added to the event for exactly this.
        assertThat(idsOf(search("پوشاک"))).contains(3L);
    }

    @Test
    @DisplayName("an exact match outranks a fuzzy one")
    void exactBeatsFuzzy() {
        // Fuzzy matching without a phrase boost puts a near-miss above the thing the shopper
        // named, which reads as the search not working.
        List<Long> ids = idsOf(search("کفش ورزشی مردانه"));

        assertThat(ids.getFirst()).isEqualTo(2L);
    }

    @Test
    @DisplayName("a withdrawn product never appears")
    void archivedIsFiltered() {
        // It is in the index - the indexer only removes what catalog flags as deleted - so the
        // filter is what keeps it off the shelf.
        assertThat(idsOf(search("کیف"))).doesNotContain(6L);
    }

    @Test
    @DisplayName("nonsense returns nothing rather than everything")
    void nonsenseMatchesNothing() {
        // The failure minimum_should_match prevents: a bool of shoulds with a filter matches every
        // document, so a typo-ridden query would return the whole shop.
        assertThat(search("zzzzqqqq").hits()).isEmpty();
    }

    @Test
    @DisplayName("an empty query browses the whole shop")
    void emptyBrowses() {
        SearchResults all = search("");

        assertThat(all.total()).isEqualTo(5);
        assertThat(idsOf(all)).doesNotContain(6L);
    }

    @Test
    @DisplayName("results can be ordered by price, in both directions")
    void priceSorts() {
        List<Long> up = idsOf(searchService.search(new SearchQuery("", null, null, SearchSort.CHEAPEST)));
        List<Long> down = idsOf(searchService.search(new SearchQuery("", null, null, SearchSort.DEAREST)));

        assertThat(up.getFirst()).isEqualTo(3L);
        assertThat(down.getFirst()).isEqualTo(5L);
    }

    @Test
    @DisplayName("newest uses the listing date")
    void newestSort() {
        assertThat(idsOf(searchService.search(new SearchQuery("", null, null, SearchSort.NEWEST)))
            .getFirst())
            .isEqualTo(5L);
    }

    @Test
    @DisplayName("paging is stable: no repeats and nothing skipped")
    void stablePaging() {
        // The tie-breaker doing its job. Without it, equally-scored documents come back in
        // whatever order the shards produced and page two repeats an item from page one.
        List<Long> first = idsOf(searchService.search(new SearchQuery("", 0, 2, SearchSort.RELEVANCE)));
        List<Long> second = idsOf(searchService.search(new SearchQuery("", 1, 2, SearchSort.RELEVANCE)));
        List<Long> third = idsOf(searchService.search(new SearchQuery("", 2, 2, SearchSort.RELEVANCE)));

        assertThat(first).hasSize(2);
        assertThat(second).hasSize(2);
        assertThat(third).hasSize(1);
        assertThat(first).doesNotContainAnyElementsOf(second);
        assertThat(first).doesNotContainAnyElementsOf(third);
        assertThat(second).doesNotContainAnyElementsOf(third);
    }

    @Test
    @DisplayName("the total counts matches, not the page")
    void totalIsTheWholeResultSet() {
        SearchResults page = searchService.search(new SearchQuery("", 0, 2, null));

        assertThat(page.hits()).hasSize(2);
        assertThat(page.total()).isEqualTo(5);
        assertThat(page.exhausted()).isTrue();
        assertThat(page.hasMore()).isTrue();
    }

    @Test
    @DisplayName("the last page reports that there is no more")
    void lastPage() {
        assertThat(searchService.search(new SearchQuery("", 2, 2, null)).hasMore()).isFalse();
    }

    @Test
    @DisplayName("a hit carries what a storefront card needs and nothing else")
    void hitShape() {
        var hit = search("کیف چرم زنانه").hits().getFirst();

        assertThat(hit.name()).isEqualTo("کیف چرم زنانه");
        assertThat(hit.slug()).isEqualTo("p1");
        assertThat(hit.minPrice()).isEqualTo(800_000L);
        assertThat(hit.maxPrice()).isEqualTo(900_000L);
        assertThat(hit.inStock()).isTrue();
        assertThat(hit.score()).isNotNull();
    }
}
