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
    },

    /**
     * Best-selling.
     *
     * <p><strong>Inert until something feeds it.</strong> {@code salesCount} is mapped and sorted
     * on, but nothing writes it — sales live in order-service and no event carries them here yet.
     * Until that feed exists this orders by the tie-breaker alone, which is stable and honest but
     * is not popularity. Left in place rather than hidden so the gap is visible in the code that
     * would have to change.
     */
    BEST_SELLING {
        @Override
        public List<SortOptions> options() {
            return List.of(field("salesCount", SortOrder.Desc), byId());
        }
    };

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
