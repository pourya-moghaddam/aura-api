package com.aura.order.discount;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * A discount code and the limits on its use.
 */
@Entity
@Table(name = "discount_codes")
@Getter
@Setter
@NoArgsConstructor
public class DiscountCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Compared case-insensitively — shoppers type these by hand, in whatever case they like. */
    @Column(nullable = false, length = 50)
    private String code;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DiscountType type;

    /** Percent for PERCENTAGE, Rial for FIXED. */
    @Column(nullable = false)
    private Long value;

    /**
     * Ceiling for a percentage discount. Without one, "50% off" against an unusually large order
     * takes an unbounded amount — the kind of thing discovered from the takings rather than from
     * an error.
     */
    @Column(name = "max_discount")
    private Long maxDiscount;

    @Column(name = "min_order_total", nullable = false)
    private Long minOrderTotal = 0L;

    /** Null means unlimited on either axis. */
    @Column(name = "usage_limit")
    private Integer usageLimit;

    @Column(name = "per_user_limit")
    private Integer perUserLimit;

    /**
     * Maintained alongside the redemption rows rather than counted from them. The counter is what
     * the usage limit is checked against while the row is locked, and counting instead would mean
     * a scan of every redemption on every checkout.
     */
    @Column(name = "times_used", nullable = false)
    private int timesUsed;

    @Column(name = "starts_at")
    private OffsetDateTime startsAt;

    @Column(name = "ends_at")
    private OffsetDateTime endsAt;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public static DiscountCode of(String code, DiscountType type, Long value) {
        DiscountCode discount = new DiscountCode();
        discount.code = code;
        discount.type = type;
        discount.value = value;
        discount.createdAt = OffsetDateTime.now();
        discount.updatedAt = discount.createdAt;
        return discount;
    }

    /** Whether the code is live right now, ignoring anything about a particular order. */
    public boolean isLiveAt(OffsetDateTime moment) {
        return isActive
            && (startsAt == null || !startsAt.isAfter(moment))
            && (endsAt == null || endsAt.isAfter(moment));
    }

    public boolean hasUsesLeft() {
        return usageLimit == null || timesUsed < usageLimit;
    }

    /**
     * What this code takes off a given subtotal.
     *
     * <p>Never more than the subtotal itself: a fixed 500,000 off a 300,000 order is a 300,000
     * discount, not a 200,000 refund. Integer arithmetic throughout, in Rial.
     */
    public long discountFor(long subtotal) {
        long raw = type == DiscountType.PERCENTAGE
            ? subtotal * value / 100
            : value;

        if (type == DiscountType.PERCENTAGE && maxDiscount != null) {
            raw = Math.min(raw, maxDiscount);
        }
        return Math.min(raw, subtotal);
    }

    public void recordUse() {
        this.timesUsed++;
        touch();
    }

    public void touch() {
        this.updatedAt = OffsetDateTime.now();
    }
}
