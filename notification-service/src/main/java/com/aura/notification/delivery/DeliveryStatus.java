package com.aura.notification.delivery;

/** Where a message got to. */
public enum DeliveryStatus {

    /** Claimed, not yet answered for. A row left here means the service died mid-send. */
    PENDING,

    SENT,

    /** Permanently refused. Retrying would be refused identically. */
    FAILED
}
