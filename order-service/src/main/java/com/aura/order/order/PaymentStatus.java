package com.aura.order.order;

/** Where an order stands with the money. Mirrors {@code ck_order_payment_status}. */
public enum PaymentStatus {

    /** Created, stock held, waiting for the gateway. */
    PENDING,

    PAID,

    FAILED,

    /** Nobody came back from the gateway and the stock hold ran out. */
    EXPIRED,

    CANCELLED
}
