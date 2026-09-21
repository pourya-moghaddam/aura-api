package com.aura.search.query.dto;

/**
 * One tick-box in the sidebar.
 *
 * @param selected whether the shopper has already chosen it. Computed here rather than left to the
 *                 storefront, which would otherwise have to compare against its own request and
 *                 get the case-folding wrong.
 */
public record FacetValue(String value, long count, boolean selected) {
}
