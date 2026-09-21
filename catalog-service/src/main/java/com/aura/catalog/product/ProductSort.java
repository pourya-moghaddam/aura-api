package com.aura.catalog.product;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * The sort orders a category page offers, and the only ones it will accept.
 *
 * <p>A closed set rather than a free {@code Sort} for two reasons, both of which bit here.
 *
 * <p>The category listing is a <em>native</em> query — it has to be, because the subtree filter
 * uses ltree's {@code <@} operator, which JPQL cannot express. Spring appends the sort to native
 * SQL verbatim, without translating entity property names to columns, so the default
 * {@code sort=createdAt} produced {@code ORDER BY p.createdat} and every category page returned
 * a 500. Property names simply are not usable there.
 *
 * <p>And because it is appended verbatim, whatever a client puts in {@code ?sort=} reaches the SQL
 * string. Restricting it to values chosen here means the request cannot name a column at all.
 */
public enum ProductSort {

    NEWEST("created_at", Sort.Direction.DESC),
    OLDEST("created_at", Sort.Direction.ASC),
    /** Cheapest first, by the denormalised range — which is why that column is maintained. */
    PRICE_ASC("min_price", Sort.Direction.ASC),
    PRICE_DESC("min_price", Sort.Direction.DESC),
    NAME("name", Sort.Direction.ASC);

    private final String column;
    private final Sort.Direction direction;

    ProductSort(String column, Sort.Direction direction) {
        this.column = column;
        this.direction = direction;
    }

    /**
     * Builds the page request. The sort is constructed here from a constant, never from anything
     * the caller supplied, so the client's only influence is which of these five it picks.
     */
    public Pageable toPageable(int page, int size) {
        int safeSize = Math.clamp(size, 1, 100);
        int safePage = Math.max(page, 0);
        // Tie-broken by id so paging is stable: two products created in the same millisecond would
        // otherwise be free to swap places between page 1 and page 2, showing one twice and hiding
        // the other.
        return PageRequest.of(safePage, safeSize,
            Sort.by(direction, column).and(Sort.by(Sort.Direction.DESC, "id")));
    }
}
