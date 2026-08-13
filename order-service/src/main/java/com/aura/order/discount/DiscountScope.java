package com.aura.order.discount;

/**
 * What a code applies to. Mirrors the {@code ck_discount_scope} check constraint.
 *
 * <p>A scoped code discounts only the lines that match, not the whole basket. "20% off shoes"
 * against a basket of shoes and a hat takes 20% of the shoes — anything else is the shop giving
 * away money it did not advertise.
 */
public enum DiscountScope {

    /** Everything in the basket. The default, and what every code was before scoping existed. */
    ORDER,

    /**
     * Products filed under one of the given categories, <em>or any descendant of them</em>. A code
     * for Clothing covers a shirt filed under Clothing → Shirts; requiring the exact category
     * would make a code stop working the moment an admin tidied the tree.
     */
    CATEGORY,

    /** Specific products. Exact ids, no inheritance — a product is not under another product. */
    PRODUCT
}
