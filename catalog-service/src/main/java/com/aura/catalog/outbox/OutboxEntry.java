package com.aura.catalog.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * An event waiting to be published.
 *
 * <p>The whole point is the row and the state change it describes are written in one transaction.
 * Sending to Kafka directly from inside {@code @Transactional} is a dual write: the commit and the
 * send can succeed independently, so a broker hiccup leaves the database changed and the event
 * gone. For a product that means the search index quietly disagrees with the catalogue — the
 * product is live but unfindable, or edited but showing its old price — with nothing anywhere
 * recording that it happened.
 *
 * <p>The trade is at-least-once delivery instead of at-most-once. That is the right way round:
 * consumers can deduplicate on {@code eventId}, but they cannot invent an event that was never
 * sent.
 */
@Entity
@Table(name = "outbox")
@Getter
@Setter
@NoArgsConstructor
public class OutboxEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Travels with the event so consumers can recognise a redelivery. */
    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    @Column(nullable = false, length = 255)
    private String topic;

    /**
     * Kafka partition key. Events for one product must land on one partition, or a stale update
     * can be consumed after a newer one and the index ends up holding the older document.
     */
    @Column(name = "partition_key", length = 255)
    private String partitionKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    /** Null while pending. Set once the broker has acknowledged the send. */
    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    public static OutboxEntry pending(UUID eventId, String topic, String partitionKey, String payload) {
        OutboxEntry entry = new OutboxEntry();
        entry.eventId = eventId;
        entry.topic = topic;
        entry.partitionKey = partitionKey;
        entry.payload = payload;
        entry.createdAt = OffsetDateTime.now();
        return entry;
    }

    public void markPublished() {
        this.publishedAt = OffsetDateTime.now();
        this.lastError = null;
    }

    /**
     * Records a failure without giving up. The row stays pending and the next sweep tries again —
     * which is the entire reason for writing it down rather than sending inline.
     */
    public void markFailed(String error) {
        this.attempts++;
        // Truncated: a stack trace from the broker client can run to kilobytes, and the useful
        // part is always at the front.
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 1000));
    }
}
