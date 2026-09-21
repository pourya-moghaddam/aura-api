package com.aura.order.discount;

/**
 * Mirrors the {@code ck_discount_type} check constraint.
 */
public enum DiscountType {

    /** {@code value} percent off, optionally capped by {@code maxDiscount}. */
    PERCENTAGE,

    /** {@code value} Rial off, flat. */
    FIXED
}
