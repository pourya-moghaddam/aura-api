package com.aura.notification.sms.smsir.dto;

import java.util.List;

public record SmsIrVerifyRequest(
    String mobile,
    int templateId,
    List<SmsIrVerifyParameter> parameters
) {
}
