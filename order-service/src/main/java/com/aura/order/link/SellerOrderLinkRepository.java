package com.aura.order.link;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SellerOrderLinkRepository extends JpaRepository<SellerOrderLink, Long> {

    /** Looked up by hash, never by raw token — the raw one is not in the database at all. */
    Optional<SellerOrderLink> findByTokenHash(String tokenHash);

    /**
     * For marking a link used. Locked so two simultaneous payments of the same link are ordered
     * rather than both finding it unused.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM SellerOrderLink l WHERE l.tokenHash = :tokenHash")
    Optional<SellerOrderLink> lockByTokenHash(@Param("tokenHash") String tokenHash);

    List<SellerOrderLink> findBySellerIdOrderByCreatedAtDesc(Long sellerId);

    Optional<SellerOrderLink> findByOrderId(Long orderId);
}
