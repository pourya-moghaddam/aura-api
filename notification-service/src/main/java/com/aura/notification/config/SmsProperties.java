package com.aura.notification.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "aura.notification.sms")
public record SmsProperties(
    SmsIrProperties smsIr
) {
    public record SmsIrProperties(
        @NotBlank(message = "SMS.ir API key must not be blank")
        String apiKey,

        @DefaultValue("823095")
        int otpTemplateId,

        @DefaultValue("https://api.sms.ir/v1")
        String baseUrl,

        @DefaultValue("/send/verify")
        String verifyPath,

        @DefaultValue("OTP")
        String otpParameterName
    ) {
    }
}
