package com.aura.order.order;

/** How the order came about. */
public enum OrderSource {

    /** An ordinary checkout from the storefront. */
    CUSTOMER,

    /** Composed by a seller and sent as a link for the buyer to pay (requirement 1). */
    SELLER_LINK
}
