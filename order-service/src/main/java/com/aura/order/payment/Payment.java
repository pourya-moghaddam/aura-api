package com.aura.order.payment;

import com.aura.order.order.PaymentStatus;
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
 * One attempt to pay for an order.
 *
 * <p>An order may have several: a shopper who abandons the gateway and comes back gets a new
 * authority, and the old attempt stays as a record of what happened rather than being overwritten.
 *
 * <p>{@code refId} and {@code cardPanMasked} come from the <em>verify</em> response only. The
 * callback carries {@code Status=OK}, which is a query parameter anyone can type into their
 * address bar, and is not proof of anything.
 */
@Entity
@Table(name = "payments")
@Getter
@Setter
@NoArgsConstructor
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(nullable = false, length = 30)
    private String gateway = "ZARINPAL";

    /** Zarinpal's handle for the attempt. Null only between creating the row and the request. */
    @Column(length = 100)
    private String authority;

    /** What was asked for, in Rial. Quoted back at verify, so it must be what we charged. */
    @Column(nullable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status = PaymentStatus.PENDING;

    /** The gateway's transaction number, for financial enquiries. Proof the money moved. */
    @Column(name = "ref_id", length = 50)
    private String refId;

    @Column(name = "card_pan_masked", length = 30)
    private String cardPanMasked;

    @Column(name = "idempotency_key", length = 100)
    private String idempotencyKey;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "verified_at")
    private OffsetDateTime verifiedAt;

    public static Payment forOrder(Long orderId, long amount) {
        Payment payment = new Payment();
        payment.orderId = orderId;
        payment.amount = amount;
        payment.status = PaymentStatus.PENDING;
        payment.createdAt = OffsetDateTime.now();
        payment.updatedAt = payment.createdAt;
        return payment;
    }

    public boolean isSettled() {
        return status != PaymentStatus.PENDING;
    }

    public void markPaid(String refId, String cardPanMasked) {
        this.status = PaymentStatus.PAID;
        this.refId = refId;
        this.cardPanMasked = cardPanMasked;
        this.verifiedAt = OffsetDateTime.now();
        touch();
    }

    public void markFailed(PaymentStatus status) {
        this.status = status;
        touch();
    }

    public void touch() {
        this.updatedAt = OffsetDateTime.now();
    }
}
