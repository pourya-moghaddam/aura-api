package com.aura.notification.sms.smsir.dto;

public record SmsIrVerifyResponse(
    int status,
    String message,
    SmsIrData data
) {
    public record SmsIrData(
        long messageId,
        double cost
    ) {
    }
}
