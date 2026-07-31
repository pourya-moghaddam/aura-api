package com.aura.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "aura.auth.otp")
public record OtpProperties(
        @DefaultValue("Your verification code is: %s") String messageTemplate,
        @DefaultValue("3") long ttlMinutes,
        @DefaultValue("otpRequestedOut-out-0") String otpBindingName
) {
}