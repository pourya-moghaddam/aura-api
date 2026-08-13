package com.aura.order.catalog;

/**
 * What catalog says about a variant order-service wants to sell.
 *
 * <p>Order-service's own record type rather than an import: the two services share a wire shape,
 * not a class. An import would tie a redeploy of one to the other and is exactly what the ArchUnit
 * boundary rule forbids.
 *
 * @param purchasable the product is live and the variant active — distinct from {@code available},
 *                    because "no longer sold" and "out of stock" are different things to tell a
 *                    shopper, and only one of them might sort itself out.
 * @param unitPrice   Rial, and the authority. A cart's stored price is only ever a hint that it
 *                    has changed.
 * @param categoryPath the line's category and every ancestor of it, root first. Carried so a
 *                    discount scoped to a parent category can be matched here, without this
 *                    service holding a copy of the tree or asking catalog again per line.
 */
public record VariantSnapshot(
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

    public VariantSnapshot {
        // Absent rather than null when catalog is an older build that does not send it yet: a
        // scoped code then simply matches nothing, which is the safe direction.
        categoryPath = categoryPath == null ? java.util.List.of() : java.util.List.copyOf(categoryPath);
    }

    /** This line, in the terms a discount is judged in. */
    public com.aura.order.discount.DiscountLine toDiscountLine(int quantity) {
        return new com.aura.order.discount.DiscountLine(
            productId, categoryId, categoryPath, unitPrice * quantity);
    }
}
