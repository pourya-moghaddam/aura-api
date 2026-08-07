package com.aura.common.events;

/**
 * Why an OTP was issued. Notification-service maps this to an SMS template.
 */
public enum OtpPurpose {

    /** Storefront sign-up or sign-in. */
    STOREFRONT_LOGIN,

    /** Control-panel sign-in. Only ever issued to users holding a control role. */
    CONTROL_LOGIN
}
