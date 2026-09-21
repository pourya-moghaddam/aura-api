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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DiscountScope scope = DiscountScope.ORDER;

    /**
     * Category or product ids, depending on the scope. They belong to catalog-service, so there is
     * nothing here for a foreign key to point at — which is exactly why they live in an array
     * rather than a join table that would imply a guarantee this database cannot make.
     */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "scope_ids", nullable = false, columnDefinition = "bigint[]")
    private Long[] scopeIds = new Long[0];

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
     * The part of a basket this code applies to.
     *
     * <p>For an order-wide code that is the whole basket. For a scoped one it is the lines that
     * match — "20% off shoes" against shoes and a hat takes 20% of the shoes, and anything else is
     * the shop giving away money it never advertised.
     *
     * <p>Category matching walks the line's ancestor path, so a code for Clothing covers a shirt
     * filed under Clothing → Shirts. Requiring the exact category would make a campaign stop
     * working the moment an admin tidied the tree.
     */
    public long eligibleSubtotal(java.util.List<DiscountLine> lines) {
        if (scope == DiscountScope.ORDER) {
            return lines.stream().mapToLong(DiscountLine::lineTotal).sum();
        }

        java.util.Set<Long> ids = scopeIdSet();
        return lines.stream()
            .filter(line -> scope == DiscountScope.PRODUCT
                ? ids.contains(line.productId())
                : line.categoryPath().stream().anyMatch(ids::contains))
            .mapToLong(DiscountLine::lineTotal)
            .sum();
    }

    public java.util.Set<Long> scopeIdSet() {
        return scopeIds == null
            ? java.util.Set.of()
            : java.util.Arrays.stream(scopeIds).collect(java.util.stream.Collectors.toSet());
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
