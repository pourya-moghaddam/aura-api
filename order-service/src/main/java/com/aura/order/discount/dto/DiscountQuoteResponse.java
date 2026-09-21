package com.aura.order.discount.dto;

/**
 * What a code would do to this order, as the checkout page shows it.
 *
 * <p>Carries nothing about the code beyond its effect — not its limits, not how many uses are
 * left, not whether it merely expired. A rejected quote is a 200 with {@code valid = false}: the
 * shopper mistyping a code is an expected outcome of a working endpoint, not an error.
 *
 * @param discountAmount Rial off, zero when invalid
 * @param newTotal       the subtotal after the discount, so the page has nothing to recompute
 */
public record DiscountQuoteResponse(
    boolean valid,
    String code,
    long discountAmount,
    long newTotal,
    String reasonCode,
    String message
) {

    public static DiscountQuoteResponse accepted(String code, long subtotal, long amount) {
        return new DiscountQuoteResponse(true, code, amount, subtotal - amount, null, null);
    }

    public static DiscountQuoteResponse rejected(String code, long subtotal, String reasonCode,
                                                 String message) {
        return new DiscountQuoteResponse(false, code, 0L, subtotal, reasonCode, message);
    }
}
