package com.aura.search.query;

import java.util.List;

/**
 * The sidebar facets, named so a filter and its own aggregation can be told apart.
 *
 * <p>That pairing is the whole of the post-filter pattern: each facet's counts are computed with
 * every <em>other</em> selection applied but not its own. Without it, choosing Navy makes every
 * other colour show zero and the shopper can never widen their choice — the sidebar becomes a
 * one-way door.
 */
public enum Facet {

    COLOR("colors", "colorNames"),
    SIZE("sizes", "sizeNames"),
    CATEGORY("categories", "categoryPath");

    private final String responseKey;
    private final String field;

    Facet(String responseKey, String field) {
        this.responseKey = responseKey;
        this.field = field;
    }

    public String responseKey() {
        return responseKey;
    }

    public String field() {
        return field;
    }

    public static List<Facet> all() {
        return List.of(values());
    }
}
