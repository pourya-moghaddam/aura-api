package com.aura.notification.otp;

import com.aura.common.events.OtpRequestedEvent;
import com.aura.notification.config.SmsProperties;
import com.aura.notification.delivery.NotificationKind;
import com.aura.notification.delivery.SmsDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * One-time codes.
 *
 * <p>Through the provider's own template rather than as free text, because Iranian operators refuse
 * free-text one-time codes outright — the template is registered with them in advance and the code
 * is the only thing we supply.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OtpNotifier {

    private final SmsDispatcher dispatcher;
    private final SmsProperties properties;

    public void send(OtpRequestedEvent event) {
        // The purpose is logged; the code never is. Development is exactly where a code is most
        // likely to be read out of a log and pasted somewhere that keeps it.
        log.debug("OTP requested for {} [{}]", event.purpose(), event.eventId());

        dispatcher.sendTemplate(
            // One event, one code, one message: the event id is exactly what "already sent" means.
            event.eventId().toString(),
            event.eventId(),
            NotificationKind.OTP,
            event.phone(),
            properties.smsIr().otpTemplateId(),
            Map.of(properties.smsIr().otpParameterName(), event.code()));
    }
}
