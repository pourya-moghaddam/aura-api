package com.aura.notification.sms;

/**
 * The provider understood the request and refused it — a bad template id, an unusable number, no
 * credit. Retrying changes nothing, so the delivery is recorded as failed and the message is not
 * sent back round the queue.
 */
public class SmsRejectedException extends RuntimeException {

    public SmsRejectedException(String message) {
        super(message);
    }
}
