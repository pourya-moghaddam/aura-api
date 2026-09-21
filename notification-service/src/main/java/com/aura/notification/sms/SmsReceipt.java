package com.aura.notification.sms;

import java.math.BigDecimal;

/**
 * What the provider said when it accepted a message.
 *
 * @param messageId the provider's own reference, so a support question can be traced into their
 *                  dashboard rather than answered with "it looks like we sent it"
 * @param cost      what it cost, where the provider reports one
 */
public record SmsReceipt(String messageId, BigDecimal cost) {
}
