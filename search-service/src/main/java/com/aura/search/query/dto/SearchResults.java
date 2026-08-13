package com.aura.search.query.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * A page of results.
 *
 * @param total    how many matched, not how many were returned. The storefront needs it to render
 *                 "۱۲۳ نتیجه" and to decide whether there is a next page.
 * @param exhausted whether the total is exact. Elasticsearch stops counting past a threshold, and
 *                 reporting a truncated count as though it were exact makes the last page of a
 *                 large result set behave strangely.
 */
public record SearchResults(
    List<SearchHit> hits,
    long total,
    boolean exhausted,
    int page,
    int size
) {

    /**
     * Whether another page exists.
     *
     * <p>Annotated because Jackson serialises a record's <em>components</em>, not its methods — so
     * without this the storefront receives a total and a page size and has to work it out itself,
     * which is precisely the arithmetic this is here to save it from. Caught by an end-to-end
     * check reading the JSON, not by any test that called the method directly.
     */
    @JsonProperty("hasMore")
    public boolean hasMore() {
        return (long) (page + 1) * size < total;
    }
}
