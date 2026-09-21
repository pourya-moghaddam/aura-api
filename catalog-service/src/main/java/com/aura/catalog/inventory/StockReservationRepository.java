package com.aura.catalog.inventory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface StockReservationRepository extends JpaRepository<StockReservation, Long> {

    List<StockReservation> findByOrderId(Long orderId);

    List<StockReservation> findByOrderIdAndStatus(Long orderId, StockReservation.ReservationStatus status);

    boolean existsByOrderIdAndStatus(Long orderId, StockReservation.ReservationStatus status);

    /**
     * Serialises everything this transaction does for one order.
     *
     * <p>Without it, {@code reserve} is a read-then-write race: two simultaneous retries of the
     * same checkout — a double-clicked button, or a client retrying a request that had in fact
     * succeeded — both find no existing hold, and both take the stock. The order ends up holding
     * twice what it asked for, and the units are unsellable until the sweep expires them.
     *
     * <p>Found by {@code StockReservationConcurrencyIT}, not by reasoning: with mocked
     * repositories the two calls serialise and the bug is invisible.
     *
     * <p>An advisory lock rather than a row lock because at this point there may be no row to lock
     * — that is precisely the case being guarded. It is scoped to the transaction, so it is
     * released on commit or rollback with nothing to remember.
     */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(:orderId)) locked", nativeQuery = true)
    Integer lockOrder(@Param("orderId") long orderId);

    /**
     * Claims a batch of expired holds for release.
     *
     * <p>{@code SKIP LOCKED} here, unlike in the inventory lock: a row another instance is already
     * releasing should be left to it rather than waited for. Missing one on this pass is harmless,
     * the sweep runs again shortly.
     *
     * <p>Ordered by expiry so the longest-overdue stock returns to the shelf first.
     */
    @Query(value = """
        SELECT * FROM stock_reservations
        WHERE status = 'HELD' AND expires_at < :now
        ORDER BY expires_at
        LIMIT :batchSize
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<StockReservation> claimExpired(@Param("now") OffsetDateTime now,
                                        @Param("batchSize") int batchSize);
}
