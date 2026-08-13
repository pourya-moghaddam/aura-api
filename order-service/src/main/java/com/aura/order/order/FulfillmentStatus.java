package com.aura.order.order;

/**
 * Where one seller's line stands. Lives on the item rather than the order because a cart can hold
 * several sellers' products and each advances only their own (requirement 8).
 */
public enum FulfillmentStatus {

    PENDING,
    PROCESSING,
    SHIPPED,
    DELIVERED,
    CANCELLED
}
