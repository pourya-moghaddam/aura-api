package com.aura.search.query;

import com.aura.search.query.dto.SearchQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The category page — requirement 13.
 *
 * <p>Almost everything it needs was built for search, and reusing that is the point: a second
 * query path would drift, and the way it drifts is that a filter works on one page and not the
 * other. What is worth testing separately is the handful of things browsing decides for itself.
 */
class CategoryBrowseIT {

    private SearchQuery request(String text, SearchSort sort) {
        return new SearchQuery(text, 0, 24, sort, null, null, null, null, null, null, null,
            List.of());
    }

    @Test
    @DisplayName("the path's category wins over anything in the query string")
    void pathWinsOverParameter() {
        // A category page is addressed by its URL. Letting a parameter contradict it would make
        // one category's page show another's products, which is the kind of thing that only shows
        // up in a bug report with a screenshot.
        SearchQuery contradictory = new SearchQuery("", 0, 24, SearchSort.RELEVANCE, 99L,
            null, null, null, null, null, null, List.of());

        assertThat(contradictory.browsing(7L).categoryId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("browsing defaults to newest, because relevance without text is not an order")
    void browseDefaultsToNewest() {
        // Every document scores the same with no query text, so relevance degenerates into the
        // tie-breaker and the shopper sees an order nobody chose.
        assertThat(request("", null).browsing(7L).sort()).isEqualTo(SearchSort.NEWEST);
        assertThat(request("", SearchSort.RELEVANCE).browsing(7L).sort())
            .isEqualTo(SearchSort.NEWEST);
    }

    @Test
    @DisplayName("a sort the shopper chose is kept")
    void keepsAChosenSort() {
        assertThat(request("", SearchSort.CHEAPEST).browsing(7L).sort())
            .isEqualTo(SearchSort.CHEAPEST);
        assertThat(request("", SearchSort.DEAREST).browsing(7L).sort())
            .isEqualTo(SearchSort.DEAREST);
    }

    @Test
    @DisplayName("searching within a category keeps relevance, because there is text to rank")
    void textInsideACategoryKeepsRelevance() {
        assertThat(request("کیف", SearchSort.RELEVANCE).browsing(7L).sort())
            .isEqualTo(SearchSort.RELEVANCE);
    }

    @Test
    @DisplayName("every other selection survives the browse")
    void carriesTheRestOfTheSidebar() {
        // Browsing is a search with a category pinned; losing the filters here would make the
        // sidebar stop working on exactly the page it matters most.
        SearchQuery filtered = new SearchQuery("", 2, 48, SearchSort.CHEAPEST, null,
            List.of("Navy"), List.of("L"), 100L, 900L, true,
            Map.of("material", List.of("Cotton")), List.of("material"));

        SearchQuery browsing = filtered.browsing(7L);

        assertThat(browsing.colors()).containsExactly("Navy");
        assertThat(browsing.sizes()).containsExactly("L");
        assertThat(browsing.minPrice()).isEqualTo(100L);
        assertThat(browsing.maxPrice()).isEqualTo(900L);
        assertThat(browsing.inStock()).isTrue();
        assertThat(browsing.attributes()).containsKey("material");
        assertThat(browsing.facetFields()).containsExactly("material");
        assertThat(browsing.page()).isEqualTo(2);
        assertThat(browsing.size()).isEqualTo(48);
    }
}
