package com.aura.search.query.dto;

import java.util.List;
import java.util.Map;

/**
 * The sidebar.
 *
 * @param price the range across the products that match everything else. Lets the storefront draw
 *              a slider with real bounds rather than guessing at them.
 */
public record Facets(
    Map<String, List<FacetValue>> fields,
    Map<String, List<FacetValue>> attributes,
    PriceRange price
) {

    public record PriceRange(Long min, Long max) {
    }

    public static Facets empty() {
        return new Facets(Map.of(), Map.of(), new PriceRange(null, null));
    }
}
