package com.aura.notification.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

        /*
         * Integer rather than int. A `.env` entry written as `SMS_IR_OTP_TEMPLATE_ID=` supplies an
         * empty string, not an absent value, so the `${...:default}` fallback in application.yml
         * never fires and the binder is asked to convert "" to a primitive. That throws
         * "A null value cannot be assigned to a primitive type" and takes the whole service down
         * at startup - which is exactly what happened on first boot.
         *
         * @NotNull rather than a silent default: sending an OTP against the wrong template is
         * worse than refusing to start, and the message says which variable is missing.
         */
        @NotNull(message = "SMS.ir OTP template id must be set (SMS_IR_OTP_TEMPLATE_ID)")
        Integer otpTemplateId,

        @DefaultValue("https://api.sms.ir/v1")
        String baseUrl,

        @DefaultValue("/send/verify")
        String verifyPath,

        @DefaultValue("OTP")
        String otpParameterName
    ) {
    }
}
