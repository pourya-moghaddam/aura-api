package com.aura.order.payment;

import com.aura.order.order.PaymentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByAuthority(String authority);

    /**
     * For settling an attempt. The lock is what makes verification idempotent under the race that
     * actually happens: the shopper's callback and the reconciliation sweep arriving at the same
     * payment within the same second, both finding it pending, and both settling it.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.authority = :authority")
    Optional<Payment> lockByAuthority(@Param("authority") String authority);

    List<Payment> findByOrderIdOrderByIdDesc(Long orderId);

    /**
     * Payments nobody came back from. Callbacks are lost — the browser is closed, the network
     * drops, the customer's phone rings — and the alternative to asking is a customer who paid and
     * got nothing.
     */
    @Query("SELECT p FROM Payment p WHERE p.status = :status AND p.createdAt < :before "
        + "AND p.authority IS NOT NULL ORDER BY p.createdAt")
    List<Payment> findStale(@Param("status") PaymentStatus status,
                            @Param("before") OffsetDateTime before,
                            Pageable pageable);
}
