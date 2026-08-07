package com.aura.common.events;

/**
 * Kafka topic names, in one place so producer and consumer configuration cannot drift.
 *
 * <p>Naming: {@code aura.<domain>.<event>.v<n>}. The version suffix is what lets an incompatible
 * schema change roll out — publish to v2 while consumers still drain v1, rather than trying to
 * change the shape of a topic in place.
 */
public final class Topics {

    public static final String OTP_REQUESTED = "aura.auth.otp-requested.v1";
    public static final String PRODUCT_CHANGED = "aura.catalog.product-changed.v1";
    public static final String ORDER_ITEM_STATUS_CHANGED = "aura.order.item-status-changed.v1";

    private Topics() {
    }
}
