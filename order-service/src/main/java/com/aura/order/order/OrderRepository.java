package com.aura.order.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByTraceCode(String traceCode);

    boolean existsByTraceCode(String traceCode);

    /** How a retried checkout finds the order it already placed instead of placing another. */
    Optional<Order> findByIdempotencyKey(String idempotencyKey);

    Page<Order> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /**
     * Serialises two checkouts carrying the same idempotency key.
     *
     * <p>Without it both pass the "have I seen this key?" check before either writes, and the
     * second is refused by {@code uq_orders_idempotency_key} — a 500 on exactly the retry the key
     * exists to make safe. With it the second waits, then finds the order the first placed.
     *
     * <p>An advisory lock rather than a row lock because there is no row to lock yet; that is the
     * case being guarded. Scoped to the transaction, so it is released on commit or rollback with
     * nothing to remember.
     */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext(:key))) locked",
        nativeQuery = true)
    Integer lockIdempotencyKey(@Param("key") String key);
}
