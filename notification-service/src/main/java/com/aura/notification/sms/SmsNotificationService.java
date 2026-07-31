package com.aura.notification.sms;

import com.aura.common.event.OtpRequestedEvent;

public interface SmsNotificationService {
    void sendSms(OtpRequestedEvent event);
}
