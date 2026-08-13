package com.aura.order.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * Runs the outbox drain and the housekeeping behind it.
 *
 * <p>Delegates to {@link OutboxPublisher} rather than doing the work here, because
 * {@code @Transactional} is applied by a proxy and a call from one method of this class to another
 * would bypass it — leaving the claim running with no transaction, where {@code FOR UPDATE SKIP
 * LOCKED} stops isolating anything and two instances happily publish the same event. That mistake
 * has already been made once in this codebase, in media-service.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxWorker {

    private static final int BATCH_SIZE = 100;

    /** Bounds one sweep so a backlog drains over several passes instead of one long transaction. */
    private static final int MAX_BATCHES_PER_RUN = 10;

    private static final int RETENTION_DAYS = 7;

    private final OutboxPublisher outboxPublisher;

    @Scheduled(fixedDelayString = "${aura.order.outbox.poll-interval:PT2S}")
    public void publishPendingEvents() {
        int total = 0;
        for (int pass = 0; pass < MAX_BATCHES_PER_RUN; pass++) {
            int published = outboxPublisher.publishBatch(BATCH_SIZE);
            total += published;
            if (published < BATCH_SIZE) {
                break;
            }
        }

        if (total > 0) {
            log.debug("Published {} outbox event(s)", total);
        }
    }

    /**
     * A backlog that is not draining means the broker is unreachable or an event is permanently
     * failing. It shows up nowhere else: sellers keep advancing orders successfully and the API
     * stays healthy while no buyer is told anything.
     */
    @Scheduled(fixedDelayString = "${aura.order.outbox.backlog-check-interval:PT1M}")
    public void reportBacklog() {
        long pending = outboxPublisher.pendingCount();
        if (pending > 1000) {
            log.error("Outbox backlog is {} events; buyers are not being notified", pending);
        } else if (pending > 100) {
            log.warn("Outbox backlog is {} events", pending);
        }
    }

    @Scheduled(cron = "${aura.order.outbox.purge-cron:0 30 3 * * *}")
    public void purgeOldPublishedEvents() {
        int removed = outboxPublisher.purgePublishedBefore(
            OffsetDateTime.now().minusDays(RETENTION_DAYS));
        if (removed > 0) {
            log.info("Purged {} published outbox event(s) older than {} days", removed, RETENTION_DAYS);
        }
    }
}
