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
 * @param categoryPath the product's category and every ancestor of it, root first. Order-service
 *                    uses it to decide whether a discount scoped to a category covers this line,
 *                    without holding a copy of the tree or asking again per line.
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
    int available,
    Long categoryId,
    java.util.List<Long> categoryPath
) {

    public static VariantSnapshotResponse from(VariantSnapshot s) {
        return new VariantSnapshotResponse(
            s.getVariantId(), s.getProductId(), s.getSellerId(),
            s.getProductName(), s.getProductSlug(), s.getColorName(), s.getSizeName(),
            s.getUnitPrice(),
            "ACTIVE".equals(s.getProductStatus()) && Boolean.TRUE.equals(s.getVariantActive()),
            s.getAvailable() == null ? 0 : s.getAvailable(),
            s.getCategoryId(), parsePath(s.getCategoryPath()));
    }

    /** The comma-separated ancestor ids the query builds, back into a list. */
    private static java.util.List<Long> parsePath(String raw) {
        if (raw == null || raw.isBlank()) {
            return java.util.List.of();
        }
        return java.util.Arrays.stream(raw.split(","))
            .map(String::trim)
            .filter(part -> !part.isEmpty())
            .map(Long::valueOf)
            .toList();
    }
}
