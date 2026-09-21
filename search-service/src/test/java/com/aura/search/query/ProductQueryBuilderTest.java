package com.aura.search.query;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.aura.search.query.dto.SearchQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shape of the query.
 *
 * <p>{@link ProductSearchIT} proves what it <em>finds</em>; this proves the clauses requirement 9
 * depends on are actually present. The two fail differently — a missing fuzziness setting shows up
 * here in milliseconds, and there as a subtly worse result ordering nobody notices.
 */
class ProductQueryBuilderTest {

    private final ProductQueryBuilder builder = new ProductQueryBuilder();

    private Query build(String text) {
        return builder.build(new SearchQuery(text, null, null, null));
    }

    @Test
    @DisplayName("an empty query matches everything, so browsing works")
    void emptyIsMatchAll() {
        assertThat(build("").isMatchAll()).isTrue();
        assertThat(build(null).isMatchAll()).isTrue();
    }

    @Test
    @DisplayName("a text query is a bool of four clauses, any of which may match")
    void fourClauses() {
        var bool = build("کیف چرم").bool();

        assertThat(bool.should()).hasSize(4);
        // Without this a bool carrying only shoulds and a filter matches everything, and a search
        // for nonsense returns the whole shop.
        assertThat(bool.minimumShouldMatch()).isEqualTo("1");
    }

    @Test
    @DisplayName("typos are forgiven, but not in the first letter")
    void fuzziness() {
        var fuzzy = build("کیف").bool().should().stream()
            .filter(Query::isMultiMatch)
            .map(Query::multiMatch)
            .filter(m -> m.fuzziness() != null)
            .findFirst().orElseThrow();

        assertThat(fuzzy.fuzziness()).isEqualTo("AUTO");
        // Without a fixed first character, short Persian words match each other freely - and short
        // words are most of a product name.
        assertThat(fuzzy.prefixLength()).isEqualTo(1);
    }

    @Test
    @DisplayName("every word must appear somewhere, across the fields together")
    void crossFields() {
        var cross = build("پیراهن نخی").bool().should().stream()
            .filter(Query::isMultiMatch)
            .map(Query::multiMatch)
            .filter(m -> m.type() != null
                && m.type() == co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType.CrossFields)
            .findFirst().orElseThrow();

        // Anything less and a two-word query is satisfied by the more common word alone.
        assertThat(cross.operator())
            .isEqualTo(co.elastic.clients.elasticsearch._types.query_dsl.Operator.And);
    }

    @Test
    @DisplayName("an exact phrase outranks everything else by a wide margin")
    void phraseBoost() {
        var phrase = build("کیف چرم").bool().should().stream()
            .filter(Query::isMatchPhrase)
            .map(Query::matchPhrase)
            .findFirst().orElseThrow();

        assertThat(phrase.field()).isEqualTo("name");
        // Fuzzy matching without this ranks a near-miss above the thing the shopper typed.
        assertThat(phrase.boost()).isGreaterThan(5.0f);
    }

    @Test
    @DisplayName("the prefix field is searched, and weighted low")
    void ngramIsWeightedDown() {
        var ngram = build("کی").bool().should().stream()
            .filter(Query::isMatch)
            .map(Query::match)
            .filter(m -> "name.ngram".equals(m.field()))
            .findFirst().orElseThrow();

        // Prefix hits find things; they should not decide what is best.
        assertThat(ngram.boost()).isLessThan(1.0f);
    }

    @Test
    @DisplayName("only buyable products are searched, and as a filter rather than a query")
    void filtersToActive() {
        var filter = builder.filters().getFirst().term();

        assertThat(filter.field()).isEqualTo("status");
        assertThat(filter.value().stringValue()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("the name outweighs the description")
    void nameIsBoosted() {
        var fields = build("کیف").bool().should().stream()
            .filter(Query::isMultiMatch)
            .flatMap(q -> q.multiMatch().fields().stream())
            .toList();

        assertThat(fields).anyMatch(f -> f.startsWith("name^"));
        assertThat(fields).contains("description", "categoryNames");
    }
}
