package com.aura.search.query.dto;

import com.aura.search.query.SearchSort;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * What a shopper asked for.
 *
 * <p>Every field is optional. An empty query is a browse — the storefront's own listing — not an
 * error, and treating it as one would mean the category page needed a different endpoint.
 *
 * @param q    the search text. Boxed and defaulted throughout, because a primitive component makes
 *             a JSON property silently mandatory in this codebase's experience.
 * @param size capped rather than trusted: an unbounded page size is a way to pull the whole
 *             catalogue in one request, and Elasticsearch's own result window would refuse it
 *             later with a less helpful message.
 */
public record SearchQuery(
    @Size(max = 200)
    String q,

    @Min(0)
    Integer page,

    @Min(1)
    @Max(100)
    Integer size,

    SearchSort sort
) {

    public SearchQuery {
        page = page == null || page < 0 ? 0 : page;
        size = size == null ? 24 : Math.min(Math.max(size, 1), 100);
        sort = sort == null ? SearchSort.RELEVANCE : sort;
        q = q == null ? "" : q.trim();
    }

    public boolean hasText() {
        return !q.isBlank();
    }

    public int from() {
        return page * size;
    }
}
