package com.aura.order.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxEntry, Long> {

    /**
     * Claims a batch of unpublished events.
     *
     * <p>Ordered by id, which for an identity column is insertion order — so events for one order
     * are published in the order they happened. Combined with a per-order partition key that is
     * what stops a buyer being told their parcel was delivered before being told it was sent.
     *
     * <p>{@code SKIP LOCKED} so several instances can drain the outbox at once without waiting on
     * each other or, worse, both publishing the same row.
     */
    @Query(value = """
        SELECT * FROM outbox
        WHERE published_at IS NULL
        ORDER BY id
        LIMIT :batchSize
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<OutboxEntry> claimPending(@Param("batchSize") int batchSize);

    long countByPublishedAtIsNull();

    /**
     * Clears out events that have been delivered.
     *
     * <p>Without this the table grows forever and the partial index on pending rows gets slower to
     * maintain. Kept for a while rather than deleted on publish, because "was this event actually
     * sent" is a question worth being able to answer after an incident.
     */
    @Modifying
    @Query("DELETE FROM OutboxEntry e WHERE e.publishedAt IS NOT NULL AND e.publishedAt < :before")
    int deletePublishedBefore(@Param("before") OffsetDateTime before);
}
