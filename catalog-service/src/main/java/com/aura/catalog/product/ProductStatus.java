package com.aura.catalog.product;

/**
 * Mirrors the {@code ck_products_status} check constraint.
 *
 * <p>There is no approval step — sellers publish directly, by decision. {@code PENDING_REVIEW}
 * would slot in here later without changing what any existing value means, which is why the column
 * is a string rather than a boolean.
 */
public enum ProductStatus {

    /** Being composed. Invisible to shoppers, and the only state in which a product may be deleted. */
    DRAFT,

    /** Live on the storefront. */
    ACTIVE,

    /**
     * Withdrawn. Kept rather than deleted because orders reference the product, and a shopper
     * looking at their order history must still see what they bought.
     */
    ARCHIVED;

    public boolean isVisibleToShoppers() {
        return this == ACTIVE;
    }
}
