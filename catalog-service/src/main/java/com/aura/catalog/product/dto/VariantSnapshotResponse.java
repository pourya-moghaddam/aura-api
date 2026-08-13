package com.aura.catalog.product.dto;

import com.aura.catalog.product.VariantSnapshotRepository.VariantSnapshot;

/**
 * What order-service is told about a variant it is about to sell.
 *
 * @param purchasable whether it can go in a cart at all: the product is live and the variant is
 *                    active. Separate from {@code available}, because "no longer sold" and "out of
 *                    stock" are different things to tell a shopper and only one of them might
 *                    resolve itself.
 * @param unitPrice   Rial. The authority on price — a cart's recorded price is only ever a hint
 *                    that it has changed.
 */
public record VariantSnapshotResponse(
    Long variantId,
    Long productId,
    Long sellerId,
    String productName,
    String productSlug,
    String colorName,
    String sizeName,
    Long unitPrice,
    boolean purchasable,
    int available
) {

    public static VariantSnapshotResponse from(VariantSnapshot s) {
        return new VariantSnapshotResponse(
            s.getVariantId(), s.getProductId(), s.getSellerId(),
            s.getProductName(), s.getProductSlug(), s.getColorName(), s.getSizeName(),
            s.getUnitPrice(),
            "ACTIVE".equals(s.getProductStatus()) && Boolean.TRUE.equals(s.getVariantActive()),
            s.getAvailable() == null ? 0 : s.getAvailable());
    }
}
