package com.aura.notification.sms;

import java.util.Map;

/**
 * Somewhere to send an SMS.
 *
 * <p>An interface so the delivery rules can be tested without a provider, and so development runs
 * against a mock instead of spending money on every OTP.
 *
 * <p>Both methods either return a receipt or throw. Returning a status code that the caller may
 * forget to read is how the previous version lost messages silently: it caught everything, logged
 * it, and returned normally, so the binder saw a successful consume and committed the offset.
 */
public interface SmsGateway {

    /**
     * Sends through a provider-side template. Iranian providers require this for one-time codes —
     * free-text OTP is refused outright by the regulator's rules.
     *
     * @throws SmsUnavailableException the provider could not be reached; worth retrying
     * @throws SmsRejectedException    the provider refused it; retrying is pointless
     */
    SmsReceipt sendTemplate(String phone, int templateId, Map<String, String> parameters);

    /** Sends free text, for messages that are not one-time codes. */
    SmsReceipt sendText(String phone, String body);
}
