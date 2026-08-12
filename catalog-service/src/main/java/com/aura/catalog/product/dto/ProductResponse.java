package com.aura.catalog.product.dto;

import com.aura.catalog.product.Product;
import com.aura.catalog.product.ProductStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * @param attributes the denormalised field values, keyed by field slug. Present so a product page
 *                   or a search document needs no further round trips.
 */
public record ProductResponse(
    Long id,
    Long sellerId,
    Long categoryId,
    String name,
    String slug,
    String description,
    ProductStatus status,
    Long minPrice,
    Long maxPrice,
    int totalStock,
    Map<String, List<String>> attributes,
    List<VariantResponse> variants,
    List<ProductMediaResponse> media,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    OffsetDateTime publishedAt
) {

    public static ProductResponse of(Product product, List<VariantResponse> variants,
                                     List<ProductMediaResponse> media) {
        return new ProductResponse(
            product.getId(), product.getSellerId(), product.getCategoryId(), product.getName(),
            product.getSlug(), product.getDescription(), product.getStatus(), product.getMinPrice(),
            product.getMaxPrice(), product.getTotalStock(), product.getAttributes(), variants, media,
            product.getCreatedAt(), product.getUpdatedAt(), product.getPublishedAt());
    }

    /** Listing shape: no variants or media bodies, for pages that show many products at once. */
    public static ProductResponse summary(Product product, List<ProductMediaResponse> media) {
        return of(product, List.of(), media);
    }
}
