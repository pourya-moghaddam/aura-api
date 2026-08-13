package com.aura.search.query;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ordering, and the tie-breaker that makes pagination stable.
 */
class SearchSortTest {

    @ParameterizedTest
    @EnumSource(SearchSort.class)
    @DisplayName("every sort ends with a stable tie-breaker")
    void everySortIsStable(SearchSort sort) {
        // Without one, equally-scored documents come back in whatever order the shards produced,
        // which differs between requests - so page two repeats an item from page one and drops
        // another. It looks like missing stock rather than a sorting bug.
        var options = sort.options();

        assertThat(options).hasSizeGreaterThanOrEqualTo(2);
        assertThat(options.getLast().field().field()).isEqualTo("productId");
    }

    @Test
    @DisplayName("relevance sorts by score")
    void relevance() {
        assertThat(SearchSort.RELEVANCE.options().getFirst().isScore()).isTrue();
    }

    @Test
    @DisplayName("newest uses when the product was listed, not when it was last edited")
    void newest() {
        // Sorting on the edit time would put a corrected typo above a product listed this morning.
        assertThat(SearchSort.NEWEST.options().getFirst().field().field()).isEqualTo("createdAt");
        assertThat(SearchSort.NEWEST.options().getFirst().field().order())
            .isEqualTo(co.elastic.clients.elasticsearch._types.SortOrder.Desc);
    }

    @Test
    @DisplayName("cheapest looks at the lowest variant price, dearest at the highest")
    void priceSorts() {
        // A product spanning 100,000 to 900,000 should appear early when sorting up and late when
        // sorting down; using one field for both would contradict that at one end.
        assertThat(SearchSort.CHEAPEST.options().getFirst().field().field()).isEqualTo("minPrice");
        assertThat(SearchSort.DEAREST.options().getFirst().field().field()).isEqualTo("maxPrice");
    }

    @ParameterizedTest
    @EnumSource(value = SearchSort.class, names = {"NEWEST", "CHEAPEST", "DEAREST"})
    @DisplayName("a product missing the sort field sorts last rather than first")
    void missingValuesSortLast(SearchSort sort) {
        assertThat(sort.options().getFirst().field().missing().stringValue()).isEqualTo("_last");
    }
}
