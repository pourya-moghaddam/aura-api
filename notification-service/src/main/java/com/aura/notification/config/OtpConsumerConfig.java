package com.aura.notification.config;

import com.aura.common.event.OtpRequestedEvent;
import com.aura.notification.sms.SmsNotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Consumer;

@Configuration
@RequiredArgsConstructor
public class OtpConsumerConfig {

    private final SmsNotificationService smsNotificationService;

    @Bean
    public Consumer<OtpRequestedEvent> otpRequestedIn() {
        return smsNotificationService::sendSms;
    }
}
