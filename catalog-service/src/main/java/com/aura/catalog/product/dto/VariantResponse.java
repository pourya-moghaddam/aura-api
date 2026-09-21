package com.aura.catalog.product.dto;

import com.aura.catalog.product.ProductVariant;

/**
 * One buyable combination of colour and size.
 *
 * @param available whether a shopper may buy this exact variant right now.
 *                  <p>A boolean, not a quantity. The storefront needs it to disable a sold-out
 *                  size before the shopper picks it — without this the page could only show the
 *                  product-wide {@code totalStock}, so every size looked available and the refusal
 *                  came from the cart after the choice had been made. Exact counts stay private:
 *                  they tell a competitor how the shop is trading, and the UI has no use for them.
 *                  <p>Necessarily a snapshot. Storefront responses are cached, so this can be a
 *                  few minutes stale — the cart remains the authority, and this exists to make the
 *                  common case not embarrassing rather than to be a reservation.
 */
public record VariantResponse(
    Long id,
    Long productId,
    Long colorId,
    Long sizeId,
    String sku,
    Long price,
    Long compareAtPrice,
    boolean isActive,
    boolean available
) {

    /**
     * Availability is a required argument rather than a defaulted field on purpose: every caller
     * has to have looked it up. A convenience overload defaulting to `true` would be wrong in the
     * one direction that matters, and wrong invisibly.
     */
    public static VariantResponse from(ProductVariant variant, boolean available) {
        return new VariantResponse(
            variant.getId(), variant.getProductId(), variant.getColorId(), variant.getSizeId(),
            variant.getSku(), variant.getPrice(), variant.getCompareAtPrice(), variant.isActive(),
            available);
    }
}
