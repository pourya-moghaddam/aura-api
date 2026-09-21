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
     * Every order, newest first — the administrative view.
     *
     * <p>Unpaid ones included, unlike {@link #findPaidOrdersForSeller}. That filter's reasoning
     * inverts here: a seller must not pack a parcel for money that has not arrived, while an
     * administrator is specifically looking for the orders stuck at exactly that point.
     */
    Page<Order> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<Order> findByPaymentStatusOrderByCreatedAtDesc(PaymentStatus paymentStatus, Pageable pageable);

    /**
     * Orders a seller has something in, newest first.
     *
     * <p>Paid only. An unpaid order is a shopper who may still be at their bank, and putting it on
     * a seller's screen has them packing parcels for money that never arrives.
     *
     * <p>An EXISTS subquery rather than a join, so an order with three of the seller's lines
     * appears once rather than three times — a join here silently multiplies the page.
     */
    @Query("""
        SELECT o FROM Order o
        WHERE o.paymentStatus = com.aura.order.order.PaymentStatus.PAID
          AND EXISTS (SELECT 1 FROM OrderItem i
                      WHERE i.orderId = o.id AND i.sellerId = :sellerId)
        ORDER BY o.createdAt DESC
        """)
    Page<Order> findPaidOrdersForSeller(@Param("sellerId") long sellerId, Pageable pageable);

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
