package com.aura.notification.delivery;

import com.aura.notification.sms.SmsGateway;
import com.aura.notification.sms.SmsReceipt;
import com.aura.notification.sms.SmsRejectedException;
import com.aura.notification.sms.SmsUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Claim, send, record. The same three steps for every kind of message.
 *
 * <p>Deliberately not transactional. The claim and the outcome each commit on their own, and the
 * call to the provider happens between them with no transaction open — holding a database
 * connection across a network call to a third party is how a slow provider becomes a connection
 * pool outage.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmsDispatcher {

    private final DeliveryLog deliveryLog;
    private final SmsGateway gateway;

    /** A one-time code, through the provider's template. */
    public void sendTemplate(String dedupeKey, UUID eventId, NotificationKind kind, String phone,
                             int templateId, Map<String, String> parameters) {
        dispatch(dedupeKey, eventId, kind, phone, templateId, null,
            () -> gateway.sendTemplate(phone, templateId, parameters));
    }

    /** Anything that is not a one-time code. */
    public void sendText(String dedupeKey, UUID eventId, NotificationKind kind, String phone,
                         String body) {
        dispatch(dedupeKey, eventId, kind, phone, null, body, () -> gateway.sendText(phone, body));
    }

    private void dispatch(String dedupeKey, UUID eventId, NotificationKind kind, String phone,
                          Integer templateId, String body, Send send) {
        if (phone == null || phone.isBlank()) {
            // Nowhere to send it. Guest orders carry a phone, but a malformed event should not
            // stall the partition behind something no retry can fix.
            log.warn("Nothing to send {} to - no phone on event {}", kind, eventId);
            return;
        }

        Optional<SmsDelivery> claimed = deliveryLog.claim(dedupeKey, eventId, kind, phone);
        if (claimed.isEmpty()) {
            return;
        }
        Long id = claimed.get().getId();

        try {
            SmsReceipt receipt = send.call();
            deliveryLog.recordSent(id, receipt, templateId, body);
            log.info("Sent {} for event {} (provider reference {})", kind, eventId,
                receipt.messageId());

        } catch (SmsRejectedException e) {
            // Permanent. Recording it and returning normally lets the offset commit: another lap
            // round the queue would be refused identically while everything behind it waits.
            deliveryLog.recordFailed(id, e.getMessage());
            log.error("{} for event {} was refused: {}", kind, eventId, e.getMessage());

        } catch (SmsUnavailableException e) {
            // Transient. Rethrowing is what makes the binder retry and, once it gives up,
            // dead-letter the message instead of dropping it - which is what the old catch-all did.
            deliveryLog.recordAttempt(id, e.getMessage());
            log.warn("{} for event {} could not be sent, will retry: {}", kind, eventId,
                e.getMessage());
            throw e;
        }
    }

    @FunctionalInterface
    private interface Send {
        SmsReceipt call();
    }
}
