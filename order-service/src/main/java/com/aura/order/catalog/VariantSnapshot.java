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
    int available
) {
}
