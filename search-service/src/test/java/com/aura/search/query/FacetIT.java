package com.aura.search.query;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.aura.common.events.ProductChangedEvent;
import com.aura.search.config.SearchProperties;
import com.aura.search.index.IndexBootstrapper;
import com.aura.search.index.IndexDefinition;
import com.aura.search.index.ProductIndexer;
import com.aura.search.query.dto.FacetValue;
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
 * The sidebar, against a real Elasticsearch.
 *
 * <p>One test here is the reason the whole class exists: {@link #choosingAColourKeepsTheOthers}.
 * Everything else is ordinary aggregation behaviour that would probably be right by accident; that
 * one fails on the naive implementation, and its failure is the sidebar becoming a one-way door.
 */
@Testcontainers
class FacetIT {

    @Container
    static final ElasticsearchContainer ELASTICSEARCH = new ElasticsearchContainer(
        DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.15.3"))
        .withEnv("xpack.security.enabled", "false")
        .withEnv("discovery.type", "single-node")
        .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m");

    private static final long CLOTHING = 1L;
    private static final long SHIRTS = 2L;
    private static final long SHOES = 3L;

    private static ProductSearchService searchService;

    @BeforeAll
    static void indexACatalogue() throws IOException {
        URI uri = URI.create("http://" + ELASTICSEARCH.getHttpHostAddress());
        ElasticsearchClient client = new ElasticsearchClient(new RestClientTransport(
            RestClient.builder(new HttpHost(uri.getHost(), uri.getPort(), "http")).build(),
            new JacksonJsonpMapper(new ObjectMapper().findAndRegisterModules())));

        SearchProperties properties = new SearchProperties(uri.toString(), "products", true);
        new IndexBootstrapper(client, new IndexDefinition(), properties).createIndexIfMissing();

        FilterBuilder filters = new FilterBuilder();
        searchService = new ProductSearchService(client, properties, new ProductQueryBuilder(),
            filters, new FacetBuilder(filters));

        ProductIndexer indexer = new ProductIndexer(client, properties);

        // Three navy shirts, two black shirts, one navy shoe. Chosen so every count below differs
        // from every other, and a wrong aggregation cannot pass by coincidence.
        indexer.apply(product(1, "پیراهن آبی", SHIRTS, List.of(CLOTHING, SHIRTS),
            List.of("Navy"), List.of("L"), Map.of("material", List.of("Cotton")), 100_000L, true));
        indexer.apply(product(2, "پیراهن آبی دیگر", SHIRTS, List.of(CLOTHING, SHIRTS),
            List.of("Navy"), List.of("M"), Map.of("material", List.of("Cotton")), 200_000L, true));
        indexer.apply(product(3, "پیراهن آبی سوم", SHIRTS, List.of(CLOTHING, SHIRTS),
            List.of("Navy"), List.of("L"), Map.of("material", List.of("Silk")), 300_000L, false));
        indexer.apply(product(4, "پیراهن مشکی", SHIRTS, List.of(CLOTHING, SHIRTS),
            List.of("Black"), List.of("L"), Map.of("material", List.of("Cotton")), 400_000L, true));
        indexer.apply(product(5, "پیراهن مشکی دیگر", SHIRTS, List.of(CLOTHING, SHIRTS),
            List.of("Black"), List.of("S"), Map.of("material", List.of("Silk")), 500_000L, true));
        indexer.apply(product(6, "کفش آبی", SHOES, List.of(SHOES),
            List.of("Navy"), List.of("42"), Map.of("material", List.of("Leather")), 900_000L, true));

        client.indices().refresh(r -> r.index("products"));
    }

    private static ProductChangedEvent product(long id, String name, long categoryId,
                                               List<Long> path, List<String> colors,
                                               List<String> sizes,
                                               Map<String, List<String>> attributes,
                                               long price, boolean inStock) {
        return new ProductChangedEvent(
            UUID.randomUUID(), Instant.now(), id, 9L, categoryId, path, List.of("پوشاک"),
            name, "p" + id, "توضیحات", "ACTIVE", price, price, inStock ? 5 : 0,
            attributes, colors, sizes, null, Instant.parse("2026-01-01T00:00:00Z"), 1_000L + id,
            false);
    }

    private SearchResults search(SearchQuery query) {
        return searchService.search(query);
    }

    private SearchQuery query(List<String> colors, List<String> sizes, Long categoryId,
                              Long minPrice, Long maxPrice, Boolean inStock,
                              Map<String, List<String>> attributes) {
        return new SearchQuery("", 0, 50, SearchSort.RELEVANCE, categoryId, colors, sizes,
            minPrice, maxPrice, inStock, attributes, List.of("material"));
    }

    private SearchQuery unfiltered() {
        return query(null, null, null, null, null, null, null);
    }

    private long countOf(List<FacetValue> values, String value) {
        return values.stream()
            .filter(v -> v.value().equalsIgnoreCase(value))
            .mapToLong(FacetValue::count)
            .findFirst().orElse(0);
    }

    private List<FacetValue> colours(SearchResults results) {
        return results.facets().fields().get("colors");
    }

    @Test
    @DisplayName("with nothing selected, every value is counted")
    void unfilteredCounts() {
        SearchResults results = search(unfiltered());

        assertThat(results.total()).isEqualTo(6);
        assertThat(countOf(colours(results), "navy")).isEqualTo(4);
        assertThat(countOf(colours(results), "black")).isEqualTo(2);
    }

    @Test
    @DisplayName("choosing a colour narrows the results but keeps the other colours countable")
    void choosingAColourKeepsTheOthers() {
        // THE test. Aggregate under the same filters as the hits and Black reports 0 here, so the
        // shopper cannot switch to it without clearing the sidebar first — they can narrow, never
        // widen. That is the failure the post-filter pattern exists to prevent.
        SearchResults results = search(query(List.of("Navy"), null, null, null, null, null, null));

        assertThat(results.total())
            .as("the hits are narrowed")
            .isEqualTo(4);
        assertThat(countOf(colours(results), "black"))
            .as("but the alternative is still offered, with a real count")
            .isEqualTo(2);
        assertThat(countOf(colours(results), "navy")).isEqualTo(4);
    }

    @Test
    @DisplayName("the chosen value is marked as chosen")
    void selectionIsMarked() {
        // Computed here rather than left to the storefront, which would have to compare against
        // its own request and get the case-folding wrong.
        List<FacetValue> values = colours(search(
            query(List.of("Navy"), null, null, null, null, null, null)));

        assertThat(values).filteredOn(v -> v.value().equalsIgnoreCase("navy"))
            .allMatch(FacetValue::selected);
        assertThat(values).filteredOn(v -> v.value().equalsIgnoreCase("black"))
            .noneMatch(FacetValue::selected);
    }

    @Test
    @DisplayName("one facet's selection does narrow another facet's counts")
    void otherFacetsAreNarrowed() {
        // The other half of the pattern, and the half that is easy to lose by over-correcting:
        // sizes must reflect the colour already chosen. Of the four navy products, two are L.
        SearchResults results = search(query(List.of("Navy"), null, null, null, null, null, null));

        assertThat(countOf(results.facets().fields().get("sizes"), "l")).isEqualTo(2);
        assertThat(countOf(results.facets().fields().get("sizes"), "s"))
            .as("a size only black products have should not be offered under navy")
            .isZero();
    }

    @Test
    @DisplayName("picking two values of one facet is an OR")
    void multipleValuesOfOneFacet() {
        // No product is two colours at once in the way a shopper means it, so ticking both boxes
        // has to widen rather than return nothing.
        SearchResults results = search(
            query(List.of("Navy", "Black"), null, null, null, null, null, null));

        assertThat(results.total()).isEqualTo(6);
    }

    @Test
    @DisplayName("selections across different facets are an AND")
    void acrossFacets() {
        SearchResults results = search(
            query(List.of("Navy"), List.of("L"), null, null, null, null, null));

        assertThat(results.total()).isEqualTo(2);
    }

    @Test
    @DisplayName("a category filter covers everything beneath it")
    void categorySubtree() {
        // Filed under Clothing > Shirts; browsing Clothing has to find them without this service
        // knowing the shape of the tree.
        assertThat(search(query(null, null, CLOTHING, null, null, null, null)).total())
            .isEqualTo(5);
        assertThat(search(query(null, null, SHIRTS, null, null, null, null)).total())
            .isEqualTo(5);
        assertThat(search(query(null, null, SHOES, null, null, null, null)).total())
            .isEqualTo(1);
    }

    @Test
    @DisplayName("a price range narrows the results")
    void priceRange() {
        assertThat(search(query(null, null, null, null, 300_000L, null, null)).total())
            .isEqualTo(3);
        assertThat(search(query(null, null, null, 400_000L, null, null, null)).total())
            .isEqualTo(3);
        assertThat(search(query(null, null, null, 200_000L, 400_000L, null, null)).total())
            .isEqualTo(3);
    }

    @Test
    @DisplayName("the price facet reports the range of what is left")
    void priceFacetFollowsTheSelection() {
        // Unlike the tick-box facets: a slider narrows to what remains rather than offering
        // alternatives, so it is computed under every selection including its own.
        var all = search(unfiltered()).facets().price();
        assertThat(all.min()).isEqualTo(100_000L);
        assertThat(all.max()).isEqualTo(900_000L);

        var shirtsOnly = search(query(null, null, SHIRTS, null, null, null, null)).facets().price();
        assertThat(shirtsOnly.max())
            .as("the 900,000 shoe is not in this category")
            .isEqualTo(500_000L);
    }

    @Test
    @DisplayName("in-stock is only applied when asked for")
    void stockFilter() {
        assertThat(search(unfiltered()).total()).isEqualTo(6);
        assertThat(search(query(null, null, null, null, null, true, null)).total()).isEqualTo(5);
    }

    @Test
    @DisplayName("dynamic attribute facets are counted and filterable")
    void attributeFacets() {
        var materials = search(unfiltered()).facets().attributes().get("material");

        assertThat(countOf(materials, "cotton")).isEqualTo(3);
        assertThat(countOf(materials, "silk")).isEqualTo(2);
        assertThat(countOf(materials, "leather")).isEqualTo(1);

        assertThat(search(query(null, null, null, null, null, null,
            Map.of("material", List.of("Cotton")))).total()).isEqualTo(3);
    }

    @Test
    @DisplayName("an attribute facet keeps its own alternatives too")
    void attributeFacetExcludesItself() {
        // The same one-way-door failure, in the dynamic facets an admin defines.
        SearchResults results = search(query(null, null, null, null, null, null,
            Map.of("material", List.of("Cotton"))));

        assertThat(results.total()).isEqualTo(3);
        assertThat(countOf(results.facets().attributes().get("material"), "silk")).isEqualTo(2);
    }

    @Test
    @DisplayName("two different attributes are an AND")
    void twoAttributesNarrow() {
        // Getting this backwards makes the sidebar return nothing as soon as two boxes are ticked.
        SearchResults results = search(query(null, null, null, null, null, null,
            Map.of("material", List.of("Cotton", "Silk"))));

        assertThat(results.total()).as("two values of one attribute widen").isEqualTo(5);
    }

    @Test
    @DisplayName("an attribute facet nobody asked for is not computed")
    void onlyRequestedAttributeFacets() {
        // Elasticsearch cannot enumerate dynamic subfields, so the storefront names the ones its
        // category has. Asking for none is legitimate and must not fail.
        SearchQuery noFacets = new SearchQuery("", 0, 10, SearchSort.RELEVANCE, null, null, null,
            null, null, null, null, List.of());

        assertThat(search(noFacets).facets().attributes()).isEmpty();
        assertThat(search(noFacets).facets().fields()).isNotEmpty();
    }

    @Test
    @DisplayName("a filter that matches nothing gives empty results and empty facets, not an error")
    void nothingMatches() {
        SearchResults results = search(
            query(List.of("Chartreuse"), null, null, null, null, null, null));

        assertThat(results.hits()).isEmpty();
        assertThat(results.total()).isZero();
        // The other colours are still offered, so the shopper can recover from a dead end.
        assertThat(countOf(colours(results), "navy")).isEqualTo(4);
    }

    @Test
    @DisplayName("the price range is absent rather than zero when nothing matches")
    void emptyPriceRange() {
        // Elasticsearch reports 0 for a min over an empty set, which a slider would draw as a real
        // bound. Read off the document count instead, so a product that genuinely costs nothing
        // stays distinguishable from no products at all.
        var price = search(query(null, null, 999L, null, null, null, null)).facets().price();

        assertThat(price.min()).isNull();
        assertThat(price.max()).isNull();
    }

    @Test
    @DisplayName("filters combine with a text query rather than replacing it")
    void filtersWithText() {
        SearchQuery textAndColour = new SearchQuery("پیراهن", 0, 50, SearchSort.RELEVANCE, null,
            List.of("Navy"), null, null, null, null, null, List.of());

        assertThat(search(textAndColour).total()).isEqualTo(3);
    }
}
