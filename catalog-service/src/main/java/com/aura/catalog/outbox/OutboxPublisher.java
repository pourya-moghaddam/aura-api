package com.aura.catalog.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Drains the outbox to Kafka.
 *
 * <p>Each row is published and marked in the same transaction that claimed it, so a crash between
 * the two leaves the row pending and the next sweep retries. That is at-least-once delivery, and
 * it is deliberate: consumers deduplicate on {@code eventId}, which they can do, rather than
 * inventing an event that never arrived, which they cannot.
 *
 * <p>A simple poller rather than CDC. Debezium reading the write-ahead log would remove the
 * polling latency, but it is a second piece of infrastructure to run and understand, and the write
 * side would not change at all if it were adopted later.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxPublisher {

    private static final String BINDING = "productChangedOut-out-0";

    /** Kafka's own header for the partition key, honoured by the binder. */
    private static final String PARTITION_KEY_HEADER = "partitionKey";

    private final OutboxRepository outboxRepository;
    private final StreamBridge streamBridge;

    /**
     * Publishes one batch.
     *
     * <p>Failures are recorded per row rather than thrown, so one poisoned event cannot block
     * every event behind it. The row keeps its place and its attempt count, which is what makes a
     * permanently failing message visible rather than merely slow.
     */
    @Transactional
    public int publishBatch(int batchSize) {
        List<OutboxEntry> pending = outboxRepository.claimPending(batchSize);
        if (pending.isEmpty()) {
            return 0;
        }

        int published = 0;
        for (OutboxEntry entry : pending) {
            try {
                streamBridge.send(BINDING, MessageBuilder
                    .withPayload(entry.getPayload())
                    .setHeader(PARTITION_KEY_HEADER, entry.getPartitionKey())
                    .setHeader("eventId", entry.getEventId().toString())
                    .build());

                entry.markPublished();
                published++;

            } catch (RuntimeException e) {
                log.warn("Could not publish outbox entry {} (attempt {}): {}",
                    entry.getId(), entry.getAttempts() + 1, e.getMessage());
                entry.markFailed(e.toString());
            }
        }

        outboxRepository.saveAll(pending);
        return published;
    }

    @Transactional
    public int purgePublishedBefore(java.time.OffsetDateTime before) {
        return outboxRepository.deletePublishedBefore(before);
    }

    @Transactional(readOnly = true)
    public long pendingCount() {
        return outboxRepository.countByPublishedAtIsNull();
    }
}
