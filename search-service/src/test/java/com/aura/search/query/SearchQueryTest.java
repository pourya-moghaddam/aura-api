package com.aura.search.query;

import com.aura.search.query.dto.SearchQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The request, and the defaults that keep a missing parameter from being an error.
 */
class SearchQueryTest {

    @Test
    @DisplayName("an entirely empty request is a browse, not a failure")
    void emptyIsBrowse() {
        // The category page and the search page are the same endpoint; if an absent q were an
        // error, browsing would need its own.
        SearchQuery query = new SearchQuery(null, null, null, null);

        assertThat(query.hasText()).isFalse();
        assertThat(query.page()).isZero();
        assertThat(query.size()).isEqualTo(24);
        assertThat(query.sort()).isEqualTo(SearchSort.RELEVANCE);
    }

    @Test
    @DisplayName("whitespace is not a search")
    void blankIsNotText() {
        assertThat(new SearchQuery("   ", null, null, null).hasText()).isFalse();
        assertThat(new SearchQuery("  کیف ", null, null, null).q()).isEqualTo("کیف");
    }

    @Test
    @DisplayName("the page size is capped, not trusted")
    void capsPageSize() {
        // An unbounded size is a way to pull the whole catalogue in one request, and
        // Elasticsearch's own result window would refuse it later with a worse message.
        assertThat(new SearchQuery(null, null, 5_000, null).size()).isEqualTo(100);
        assertThat(new SearchQuery(null, null, 0, null).size()).isEqualTo(1);
        assertThat(new SearchQuery(null, null, -3, null).size()).isEqualTo(1);
    }

    @Test
    @DisplayName("a negative page is the first page")
    void negativePage() {
        assertThat(new SearchQuery(null, -2, null, null).page()).isZero();
    }

    @Test
    @DisplayName("the offset is page times size")
    void offset() {
        assertThat(new SearchQuery(null, 3, 20, null).from()).isEqualTo(60);
    }
}
