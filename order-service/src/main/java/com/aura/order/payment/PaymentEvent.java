package com.aura.order.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/**
 * Everything the gateway ever said about a payment, kept verbatim.
 *
 * <p>Written on every request, callback and verify — including the failures. When a customer says
 * they were charged and the order says otherwise, this is the only record of what actually passed
 * between the two systems, and it is worth far more than the disk it costs.
 */
@Entity
@Table(name = "payment_events")
@Getter
@Setter
@NoArgsConstructor
public class PaymentEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(nullable = false, length = 40)
    private String type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", nullable = false, columnDefinition = "jsonb")
    private String rawPayload;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    public static PaymentEvent of(Long paymentId, String type, String rawPayload) {
        PaymentEvent event = new PaymentEvent();
        event.paymentId = paymentId;
        event.type = type;
        event.rawPayload = rawPayload == null ? "{}" : rawPayload;
        event.createdAt = OffsetDateTime.now();
        return event;
    }
}
