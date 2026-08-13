package com.aura.search.query.dto;

import com.aura.search.index.ProductDocument;

import java.util.List;

/**
 * One result, as a storefront card needs it.
 *
 * <p>Narrower than the stored document: the description and the seller are not on a card, and
 * sending them multiplies the payload of a results page by the page size for nothing.
 */
public record SearchHit(
    Long productId,
    String name,
    String slug,
    Long minPrice,
    Long maxPrice,
    boolean inStock,
    String primaryMediaId,
    List<String> colorNames,
    Double score
) {

    public static SearchHit from(ProductDocument document, Double score) {
        return new SearchHit(
            document.productId(), document.name(), document.slug(),
            document.minPrice(), document.maxPrice(), document.inStock(),
            document.primaryMediaId(), document.colorNames(), score);
    }
}
