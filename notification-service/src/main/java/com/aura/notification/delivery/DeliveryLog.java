package com.aura.notification.delivery;

import com.aura.notification.sms.SmsReceipt;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * The record of what was sent.
 *
 * <p>Every method here commits on its own — {@code REQUIRES_NEW} — and that is the point rather
 * than an oversight. The claim has to survive a send that then fails and rethrows, because a
 * transaction spanning both would roll the row back and the retry would arrive with no memory of
 * the previous attempt. The counter would read 1 forever and a permanently broken message would
 * look brand new every time.
 */
@Slf4j
@Service
public class DeliveryLog {

    private final SmsDeliveryRepository repository;
    private final TransactionTemplate transactionTemplate;

    public DeliveryLog(SmsDeliveryRepository repository, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(
            TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Takes ownership of sending this event, or declines because someone already did.
     *
     * <p>Idempotency lives here. Kafka delivers at least once, and an SMS is not a database write:
     * it costs money and it arrives on a real phone. A second OTP is worse than merely wasteful,
     * because the shopper now holds two codes and cannot tell which one the login will accept.
     *
     * <p>The unique constraint does the deciding, not a prior read — two consumer threads that both
     * check first and then insert will both send.
     *
     * <p>Two explicit transactions rather than one annotated method, and that is load-bearing: a
     * constraint violation marks its transaction rollback-only, so reading the winning row in the
     * same one fails at commit with an unrelated-looking error. The insert has to be finished with
     * before the lookup starts.
     *
     * @param dedupeKey what "already sent" means for this message — usually the event id, but the
     *                  order id for a message about an order rather than about one of its items
     * @return the row to send against, or empty when this key has already been settled
     */
    public Optional<SmsDelivery> claim(String dedupeKey, UUID eventId, NotificationKind kind,
                                       String phone) {
        try {
            return Optional.ofNullable(transactionTemplate.execute(status ->
                repository.saveAndFlush(
                    SmsDelivery.claimed(dedupeKey, eventId, kind, phone))));

        } catch (DataIntegrityViolationException e) {
            // Someone got here first. Whether that is a redelivery, a parallel consumer, or a
            // second item from an order that has just been delivered, the answer is the same: do
            // not send a second message.
            SmsDelivery existing = transactionTemplate.execute(status ->
                repository.findByDedupeKey(dedupeKey).orElse(null));

            if (existing == null) {
                throw new IllegalStateException(
                    "Delivery " + dedupeKey + " both exists and does not", e);
            }
            if (existing.isSettled()) {
                log.debug("Ignoring a repeat of {} - already {}", dedupeKey, existing.getStatus());
                return Optional.empty();
            }

            // Left PENDING by an attempt that died mid-flight. Worth finishing: the alternative is
            // a message that is never sent because a pod restarted at the wrong moment.
            return Optional.of(existing);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSent(Long id, SmsReceipt receipt, Integer templateId, String body) {
        SmsDelivery delivery = load(id);
        delivery.setStatus(DeliveryStatus.SENT);
        delivery.setProviderMessageId(receipt.messageId());
        delivery.setProviderCost(receipt.cost());
        delivery.setTemplateId(templateId);
        delivery.setBody(body);
        delivery.setAttempts(delivery.getAttempts() + 1);
        delivery.setSentAt(OffsetDateTime.now());
        delivery.setUpdatedAt(delivery.getSentAt());
        repository.save(delivery);
    }

    /** A refusal that another attempt would meet identically. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailed(Long id, String error) {
        SmsDelivery delivery = load(id);
        delivery.setStatus(DeliveryStatus.FAILED);
        delivery.setAttempts(delivery.getAttempts() + 1);
        delivery.setLastError(truncate(error));
        delivery.setUpdatedAt(OffsetDateTime.now());
        repository.save(delivery);
    }

    /**
     * A failure worth retrying. Stays PENDING, so the next delivery of the same event picks the row
     * up again rather than being turned away as a duplicate.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAttempt(Long id, String error) {
        SmsDelivery delivery = load(id);
        delivery.setAttempts(delivery.getAttempts() + 1);
        delivery.setLastError(truncate(error));
        delivery.setUpdatedAt(OffsetDateTime.now());
        repository.save(delivery);
    }

    private SmsDelivery load(Long id) {
        return repository.findById(id)
            .orElseThrow(() -> new IllegalStateException("Delivery " + id + " vanished"));
    }

    /** Providers sometimes answer with a whole HTML error page; the column is not a log file. */
    private String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 1000 ? error : error.substring(0, 1000);
    }
}
