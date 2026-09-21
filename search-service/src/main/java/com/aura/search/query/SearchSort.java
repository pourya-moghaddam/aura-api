package com.aura.search.query;

import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;

import java.util.List;

/**
 * How a shopper can order results.
 *
 * <p>A closed enum rather than a field name from the request. A sort parameter that reaches
 * Elasticsearch unchecked is both a way to sort by a field that is not indexed for it — which
 * fails the whole query — and a way to learn what fields exist.
 */
public enum SearchSort {

    /**
     * What the query thinks is best. The default, because for a text search nothing else is
     * useful: sorting by price throws away the reason the results matched at all.
     */
    RELEVANCE {
        @Override
        public List<SortOptions> options() {
            return List.of(byScore(), byId());
        }
    },

    /** Newest listings first, by when the product was first listed rather than last edited. */
    NEWEST {
        @Override
        public List<SortOptions> options() {
            return List.of(field("createdAt", SortOrder.Desc), byId());
        }
    },

    CHEAPEST {
        @Override
        public List<SortOptions> options() {
            return List.of(field("minPrice", SortOrder.Asc), byId());
        }
    },

    DEAREST {
        @Override
        public List<SortOptions> options() {
            return List.of(field("maxPrice", SortOrder.Desc), byId());
        }
    };

    // There is deliberately no BEST_SELLING here. Sorting by popularity needs a sales figure inside
    // each document, and the indexer replaces documents wholesale under an external version — a
    // count written by anything else is erased by the next product edit, and preserving it would
    // mean scripted updates, which cannot carry an external version and so give up the ordering
    // guarantee that stops a stale event overwriting fresh data. Sales counts therefore live in
    // Redis, and "what is selling" is answered by the trending endpoint instead of by a sort option
    // that would quietly return an arbitrary order.

    public abstract List<SortOptions> options();

    /**
     * The tie-breaker, on every sort.
     *
     * <p>Without one, documents with equal scores or equal prices come back in whatever order the
     * shards happened to produce — which differs between requests, so page two can repeat an item
     * from page one and omit another entirely. It is the classic pagination bug and it looks like
     * missing stock rather than a sorting problem.
     */
    private static SortOptions byId() {
        return SortOptions.of(s -> s.field(f -> f.field("productId").order(SortOrder.Desc)));
    }

    private static SortOptions byScore() {
        return SortOptions.of(s -> s.score(sc -> sc.order(SortOrder.Desc)));
    }

    private static SortOptions field(String name, SortOrder order) {
        return SortOptions.of(s -> s.field(f -> f
            .field(name)
            .order(order)
            // A product with no value for the field sorts last rather than failing the query or
            // silently leading the list. "_last" rather than a sentinel number, so it works the
            // same for a date field as for a price.
            .missing(m -> m.stringValue("_last"))));
    }
}
