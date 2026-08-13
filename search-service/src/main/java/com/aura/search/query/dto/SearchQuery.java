package com.aura.search.query.dto;

import com.aura.search.query.SearchSort;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a shopper asked for: some text, some sidebar selections, and where in the results they are.
 *
 * <p>Every field is optional. An empty query is a browse — the storefront's own listing — not an
 * error, and treating it as one would mean the category page needed a different endpoint.
 *
 * @param categoryId  browse a category and everything beneath it. Matched against the indexed
 *                    ancestor path, so one term covers a whole subtree.
 * @param attributes  dynamic field filters, bound as {@code attributes[material]=Leather}. A map
 *                    rather than named parameters because the fields are defined by an admin per
 *                    category and this service cannot know them in advance.
 * @param facetFields which attribute facets to count. The storefront knows a category's fields
 *                    from catalog and asks for those; Elasticsearch cannot enumerate dynamic
 *                    subfields in one pass, and guessing would either miss facets or cost a second
 *                    round trip on every search.
 * @param size        capped rather than trusted: an unbounded page size is a way to pull the whole
 *                    catalogue in one request.
 */
public record SearchQuery(
    @Size(max = 200)
    String q,

    @Min(0)
    Integer page,

    @Min(1)
    @Max(100)
    Integer size,

    SearchSort sort,

    Long categoryId,

    List<String> colors,

    List<String> sizes,

    @Min(0)
    Long minPrice,

    @Min(0)
    Long maxPrice,

    Boolean inStock,

    Map<String, List<String>> attributes,

    List<String> facetFields
) {

    public SearchQuery {
        page = page == null || page < 0 ? 0 : page;
        size = size == null ? 24 : Math.min(Math.max(size, 1), 100);
        sort = sort == null ? SearchSort.RELEVANCE : sort;
        q = q == null ? "" : q.trim();
        colors = colors == null ? List.of() : List.copyOf(colors);
        sizes = sizes == null ? List.of() : List.copyOf(sizes);
        facetFields = facetFields == null ? List.of() : List.copyOf(facetFields);
        attributes = attributes == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(attributes));
    }

    /** The plain form, kept so an ordinary search reads as one. */
    public SearchQuery(String q, Integer page, Integer size, SearchSort sort) {
        this(q, page, size, sort, null, null, null, null, null, null, null, null);
    }

    public boolean hasText() {
        return !q.isBlank();
    }

    public int from() {
        return page * size;
    }

    public boolean hasPriceRange() {
        return minPrice != null || maxPrice != null;
    }
}
