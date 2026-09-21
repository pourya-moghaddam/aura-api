package com.aura.order.order;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * A placed order.
 *
 * <p>Almost everything here is a <em>snapshot</em> rather than a reference: the buyer's name and
 * address, the delivery method's name and fee, the discount code as typed. The address book lives
 * in auth-service and its rows are editable, and an admin may reprice delivery tomorrow — neither
 * may rewrite what this customer was told they were paying, or where it was sent.
 *
 * <p>Money is Rial as a whole number throughout.
 */
@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** What the customer quotes. Unrelated to the id — see {@link TraceCodes}. */
    @Column(name = "trace_code", nullable = false, length = 16, updatable = false)
    private String traceCode;

    /** Null for a guest checkout, which is requirement 12's whole point. */
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "buyer_phone", nullable = false, length = 20)
    private String buyerPhone;

    @Column(name = "buyer_first_name", nullable = false, length = 100)
    private String buyerFirstName;

    @Column(name = "buyer_last_name", nullable = false, length = 100)
    private String buyerLastName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "address_snapshot", nullable = false, columnDefinition = "jsonb")
    private Map<String, String> addressSnapshot;

    @Column(name = "postal_code", nullable = false, length = 10)
    private String postalCode;

    @Column(name = "delivery_method_id")
    private Long deliveryMethodId;

    @Column(name = "delivery_name", nullable = false, length = 100)
    private String deliveryName;

    @Column(name = "delivery_fee", nullable = false)
    private Long deliveryFee;

    @Column(name = "discount_code_id")
    private Long discountCodeId;

    @Column(name = "discount_code", length = 50)
    private String discountCode;

    @Column(name = "discount_amount", nullable = false)
    private Long discountAmount = 0L;

    @Column(nullable = false)
    private Long subtotal;

    @Column(nullable = false)
    private Long total;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 20)
    private PaymentStatus paymentStatus = PaymentStatus.PENDING;

    /** Derived from the items, never set directly — see {@link #deriveStatusFrom}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "derived_status", nullable = false, length = 20)
    private FulfillmentStatus derivedStatus = FulfillmentStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderSource source = OrderSource.CUSTOMER;

    /**
     * The client's key for this checkout. A retried request carrying the same key returns the
     * original order rather than buying twice — a browser retrying a timed-out POST is ordinary,
     * and without this it is a second charge.
     */
    @Column(name = "idempotency_key", length = 100)
    private String idempotencyKey;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "paid_at")
    private OffsetDateTime paidAt;

    public String buyerName() {
        return buyerFirstName + " " + buyerLastName;
    }

    public void touch() {
        this.updatedAt = OffsetDateTime.now();
    }

    /**
     * The order is only as far along as its least-advanced item.
     *
     * <p>Cancelled items are ignored unless every item is cancelled: one seller cancelling their
     * line must not make the whole order look cancelled to a buyer still waiting on the rest.
     */
    public void deriveStatusFrom(java.util.Collection<OrderItem> items) {
        if (items.isEmpty()) {
            return;
        }
        boolean allCancelled = items.stream()
            .allMatch(item -> item.getFulfillmentStatus() == FulfillmentStatus.CANCELLED);

        if (allCancelled) {
            this.derivedStatus = FulfillmentStatus.CANCELLED;
            return;
        }
        this.derivedStatus = items.stream()
            .map(OrderItem::getFulfillmentStatus)
            .filter(status -> status != FulfillmentStatus.CANCELLED)
            .min(java.util.Comparator.comparingInt(Enum::ordinal))
            .orElse(FulfillmentStatus.PENDING);
    }
}
