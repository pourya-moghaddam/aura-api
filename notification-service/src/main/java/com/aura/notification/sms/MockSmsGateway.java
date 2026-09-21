package com.aura.notification.sms;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Accepts everything and sends nothing.
 *
 * <p>The default, so a checkout of this repository does not call a paid provider — and, worse, does
 * not text a real Iranian phone number that happens to be in someone's test fixture.
 *
 * <p>It logs the recipient but never the code. Development is exactly where an OTP is most likely
 * to be read out of a log and pasted somewhere it will be kept; the code can be read from
 * auth-service's own table by anyone who legitimately needs it.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "aura.notification.sms.mode", havingValue = "mock",
    matchIfMissing = true)
public class MockSmsGateway implements SmsGateway {

    @Override
    public SmsReceipt sendTemplate(String phone, int templateId, Map<String, String> parameters) {
        log.info("MOCK SMS: template {} to {} ({} parameter(s)) - nothing was sent",
            templateId, phone, parameters.size());
        return receipt();
    }

    @Override
    public SmsReceipt sendText(String phone, String body) {
        log.info("MOCK SMS to {}: {}", phone, body);
        return receipt();
    }

    /** Prefixed, so a mock reference can never be mistaken for one the provider would honour. */
    private SmsReceipt receipt() {
        return new SmsReceipt("mock-" + UUID.randomUUID(), BigDecimal.ZERO);
    }
}
