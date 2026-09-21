package com.aura.order.discount;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * One order's use of one code, and the amount it actually took off.
 *
 * <p>The amount is stored rather than recomputed: the code's percentage can be edited afterwards,
 * and an order has to keep saying what the customer was charged. {@code uq_redemption_order} caps
 * this at one discount per order — and doubles as the backstop that makes redemption idempotent
 * even if the service layer were called twice.
 */
@Entity
@Table(name = "discount_redemptions")
@Getter
@Setter
@NoArgsConstructor
public class DiscountRedemption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "discount_code_id", nullable = false)
    private Long discountCodeId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    /** Null for a guest — there is no account to attribute the use to. */
    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false)
    private Long amount;

    @Column(name = "redeemed_at", nullable = false)
    private OffsetDateTime redeemedAt;

    public static DiscountRedemption of(Long discountCodeId, Long orderId, Long userId, long amount) {
        DiscountRedemption redemption = new DiscountRedemption();
        redemption.discountCodeId = discountCodeId;
        redemption.orderId = orderId;
        redemption.userId = userId;
        redemption.amount = amount;
        redemption.redeemedAt = OffsetDateTime.now();
        return redemption;
    }
}
