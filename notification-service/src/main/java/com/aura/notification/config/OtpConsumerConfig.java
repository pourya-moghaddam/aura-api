package com.aura.notification.config;

import com.aura.common.events.OtpRequestedEvent;
import com.aura.notification.otp.OtpNotifier;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Consumer;

@Configuration
@RequiredArgsConstructor
public class OtpConsumerConfig {

    private final OtpNotifier otpNotifier;

    /**
     * Exceptions are deliberately allowed to escape. The binder retries and then dead-letters,
     * which is the only reason a transient provider failure ever gets a second chance — the
     * previous version caught everything here and the message was lost silently.
     */
    @Bean
    public Consumer<OtpRequestedEvent> otpRequestedIn() {
        return otpNotifier::send;
    }
}
