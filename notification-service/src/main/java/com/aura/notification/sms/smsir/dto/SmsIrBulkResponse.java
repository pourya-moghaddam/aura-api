package com.aura.notification.sms.smsir.dto;

import java.util.List;

public record SmsIrBulkResponse(
    int status,
    String message,
    SmsIrBulkData data
) {
    public record SmsIrBulkData(
        long packId,
        List<Long> messageIds,
        double cost
    ) {
    }
}
