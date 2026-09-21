package com.aura.notification.sms.smsir.dto;

import java.util.List;

/**
 * A free-text send.
 *
 * @param lineNumber the sender line the account owns. Absent for a one-time code, which goes
 *                   through a template and a shared line instead.
 */
public record SmsIrBulkRequest(
    String lineNumber,
    String messageText,
    List<String> mobiles
) {
}
