package com.aura.search.query;

import com.aura.search.query.dto.SearchResults;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The response as a storefront actually receives it.
 *
 * <p>Written after {@code hasMore} turned out to be missing from the JSON: Jackson serialises a
 * record's components, not its methods, so a derived value needs saying so explicitly. Asserting
 * on the serialised form rather than the object is the only way to notice.
 */
class SearchResultsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private SearchResults page(int page, int size, long total) {
        return new SearchResults(List.of(), total, true, page, size);
    }

    @Test
    @DisplayName("hasMore reaches the client")
    void hasMoreIsSerialised() throws Exception {
        assertThat(mapper.readTree(mapper.writeValueAsString(page(0, 10, 100))).has("hasMore"))
            .isTrue();
    }

    @Test
    @DisplayName("there is more when the page does not reach the total")
    void hasMoreWhenMorePages() {
        assertThat(page(0, 10, 100).hasMore()).isTrue();
        assertThat(page(9, 10, 100).hasMore()).isFalse();
    }

    @Test
    @DisplayName("an exactly-full last page reports no more")
    void exactlyFullLastPage() {
        // The off-by-one that shows the shopper an empty page after the last real one.
        assertThat(page(4, 20, 100).hasMore()).isFalse();
    }

    @Test
    @DisplayName("no results means no more")
    void empty() {
        assertThat(page(0, 20, 0).hasMore()).isFalse();
    }
}
