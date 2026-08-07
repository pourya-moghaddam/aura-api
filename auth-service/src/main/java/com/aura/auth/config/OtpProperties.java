package com.aura.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Message copy is deliberately absent: notification-service owns templates and selects them from
 * the event's purpose. sms.ir in particular renders from a server-side template ID, so a rendered
 * message string built here was never sent anywhere.
 */
@ConfigurationProperties(prefix = "aura.auth.otp")
public record OtpProperties(
    @DefaultValue("3") long ttlMinutes,
    @DefaultValue("otpRequestedOut-out-0") String otpBindingName
) {
}
