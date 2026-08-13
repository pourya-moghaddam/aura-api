package com.aura.search.query;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import com.aura.search.query.dto.SearchQuery;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Requirement 9, expressed as an Elasticsearch query.
 *
 * <p>The requirement is two sentences — "words can be in any part of the name" and "similar, not
 * exact" — and each needs a different clause, which is why this is a {@code bool} of several
 * {@code should}s rather than one clever query:
 *
 * <ul>
 *   <li><strong>best_fields with fuzziness</strong> is the "similar, not exact" half. One or two
 *       character edits are forgiven, and {@code prefix_length: 1} keeps the first letter fixed —
 *       without it, "کیف" matches "کفش" and the results stop making sense.</li>
 *   <li><strong>cross_fields</strong> is the "any part" half. It treats the fields as one big
 *       field, so "پیراهن نخی" matches a product whose name has one word and whose description
 *       has the other. No fuzziness here: Elasticsearch does not support it for this type, and
 *       asking silently changes the query type rather than failing.</li>
 *   <li><strong>a phrase match on the name</strong>, boosted hard, so an exact hit still comes
 *       first. Fuzzy matching without this ranks a near-miss above the thing the shopper typed.</li>
 *   <li><strong>the edge-ngram field</strong>, weighted low, for as-you-type. It matches prefixes
 *       of every word, which is useful for finding something and useless for ranking it.</li>
 * </ul>
 *
 * <p>An empty query is a {@code match_all}: browsing is the same endpoint as searching, and the
 * category page depends on that.
 */
@Component
public class ProductQueryBuilder {

    /**
     * An exact phrase is worth more than any amount of fuzzy agreement. Chosen high rather than
     * tuned: the failure it prevents — a typo-tolerant near-match outranking the exact product a
     * shopper named — is far more visible than imperfect ordering among genuine matches.
     */
    private static final float PHRASE_BOOST = 8.0f;

    private static final float NAME_BOOST = 3.0f;

    /** Prefix hits find things; they should not decide what is best. */
    private static final float NGRAM_BOOST = 0.3f;

    public Query build(SearchQuery request) {
        if (!request.hasText()) {
            return Query.of(q -> q.matchAll(m -> m));
        }

        String text = request.q();

        return Query.of(q -> q.bool(b -> b
            .should(fuzzyAcrossFields(text))
            .should(everyWordSomewhere(text))
            .should(exactPhrase(text))
            .should(asYouType(text))
            // At least one has to match. Without it a bool with only should clauses and a filter
            // would match everything, and a search for nonsense would return the whole shop.
            .minimumShouldMatch("1")));
    }

    /** "Similar, not exact." */
    private Query fuzzyAcrossFields(String text) {
        return Query.of(q -> q.multiMatch(m -> m
            .query(text)
            .fields("name^" + NAME_BOOST, "description", "categoryNames")
            .type(TextQueryType.BestFields)
            .fuzziness("AUTO")
            // The first character is not a typo. Allowing it to be one makes short Persian words
            // match each other freely, and short words are most of a product name.
            .prefixLength(1)));
    }

    /** "Words can be in any part of the name" — or in the description, or in the category. */
    private Query everyWordSomewhere(String text) {
        return Query.of(q -> q.multiMatch(m -> m
            .query(text)
            .fields("name^2", "description", "categoryNames")
            .type(TextQueryType.CrossFields)
            // Every word has to appear somewhere. Anything less and a two-word query is satisfied
            // by the more common of the two, which is usually the useless one.
            .operator(co.elastic.clients.elasticsearch._types.query_dsl.Operator.And)));
    }

    private Query exactPhrase(String text) {
        return Query.of(q -> q.matchPhrase(m -> m
            .field("name")
            .query(text)
            .boost(PHRASE_BOOST)));
    }

    private Query asYouType(String text) {
        return Query.of(q -> q.match(m -> m
            .field("name.ngram")
            .query(text)
            .boost(NGRAM_BOOST)));
    }

    /** Only things a shopper can actually buy. */
    public List<Query> filters() {
        return List.of(Query.of(q -> q.term(t -> t.field("status").value("ACTIVE"))));
    }
}
