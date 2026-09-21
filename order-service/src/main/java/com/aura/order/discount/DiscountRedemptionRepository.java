package com.aura.order.discount;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DiscountRedemptionRepository extends JpaRepository<DiscountRedemption, Long> {

    /** How many times one account has used one code, for the per-user limit. */
    int countByDiscountCodeIdAndUserId(Long discountCodeId, Long userId);

    Optional<DiscountRedemption> findByOrderId(Long orderId);
}
