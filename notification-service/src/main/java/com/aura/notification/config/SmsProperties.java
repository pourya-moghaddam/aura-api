package com.aura.notification.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * @param mode {@code http} talks to sms.ir; {@code mock} accepts everything and sends nothing.
 *             Mock is the default so a fresh checkout cannot spend money — or, worse, text a real
 *             Iranian number that happens to be in someone's test fixture. Unlike the payment
 *             gateway's mock, this one fails safe: the wrong setting means a shopper waits for a
 *             code that never comes, which is loud, rather than an order marked paid for nothing.
 */
@Validated
@ConfigurationProperties(prefix = "aura.notification.sms")
public record SmsProperties(
    @DefaultValue("mock") String mode,
    SmsIrProperties smsIr
) {

    public boolean isMock() {
        return "mock".equalsIgnoreCase(mode);
    }

    public record SmsIrProperties(
        /*
         * Not @NotBlank: the key is only needed in http mode, and requiring it everywhere means a
         * developer cannot start the service without a live credential. SmsIrGateway refuses to
         * construct without it, which puts the check where the key is actually used.
         */
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

        /* Free text, for anything that is not a one-time code. Iranian providers keep the two
         * apart: OTP must go through an approved template, ordinary messages must not. */
        @DefaultValue("/send/bulk")
        String bulkPath,

        /* The sender line the account owns. Only free-text sends need one; templates use the
         * provider's shared line. */
        String lineNumber,

        @DefaultValue("OTP")
        String otpParameterName
    ) {
    }
}
