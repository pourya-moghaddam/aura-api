package com.aura.notification.delivery;

/** What a message is for. Kept out of the provider layer, which only knows about phones and text. */
public enum NotificationKind {

    OTP,

    ORDER_STATUS
}
