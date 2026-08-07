package com.aura.notification.sms;

import com.aura.common.events.OtpRequestedEvent;

public interface SmsNotificationService {
    void sendOtp(OtpRequestedEvent event);
}
