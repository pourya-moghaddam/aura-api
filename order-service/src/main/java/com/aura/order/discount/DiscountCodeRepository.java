package com.aura.order.discount;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface DiscountCodeRepository extends JpaRepository<DiscountCode, Long> {

    /**
     * For previews and admin reads. No lock: a preview is advisory, and taking a row lock on every
     * keystroke in the discount box would serialise checkout behind whoever is typing.
     */
    Optional<DiscountCode> findByCodeIgnoreCase(String code);

    /**
     * For redemption. {@code SELECT ... FOR UPDATE} on the code row, so two checkouts racing for
     * the last use of a limited code are ordered rather than both reading {@code times_used = 0}.
     *
     * <p>The lock is on the code, not on the order, because the code is what is scarce. Whichever
     * transaction arrives second blocks here, then re-reads the incremented counter and is refused.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM DiscountCode d WHERE LOWER(d.code) = LOWER(:code)")
    Optional<DiscountCode> lockByCode(@Param("code") String code);

    Page<DiscountCode> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
