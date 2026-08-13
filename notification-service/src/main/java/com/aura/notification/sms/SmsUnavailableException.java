package com.aura.notification.sms;

/**
 * The provider could not be reached, or failed in a way that another attempt might survive.
 *
 * <p>Separate from {@link SmsRejectedException} because the two demand opposite responses: this one
 * should be retried and eventually dead-lettered, and retrying the other would be refused
 * identically every time while the queue stalls behind it.
 */
public class SmsUnavailableException extends RuntimeException {

    public SmsUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public SmsUnavailableException(String message) {
        super(message);
    }
}
