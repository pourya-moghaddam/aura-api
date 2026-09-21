package com.aura.notification.delivery;

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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One message, and what became of it.
 *
 * <p>Note what this does not hold: the OTP code. A one-time code written to a table outlives its
 * validity window, and a database dump then contains working credentials. The code exists in
 * transit and nowhere else.
 */
@Entity
@Table(name = "sms_deliveries")
@Getter
@Setter
@NoArgsConstructor
public class SmsDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * What decides whether to send.
     *
     * <p>Usually the event id, but not always: one delivered order raises an event per item, and
     * the shopper should hear about the parcel once. See V2__dedupe_key.sql.
     */
    @Column(name = "dedupe_key", nullable = false, unique = true, length = 120)
    private String dedupeKey;

    /** What caused it. The thread back to a log line and a Kafka offset. */
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NotificationKind kind;

    @Column(nullable = false, length = 20)
    private String phone;

    @Column(name = "template_id")
    private Integer templateId;

    @Column
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DeliveryStatus status;

    @Column(name = "provider_message_id", length = 100)
    private String providerMessageId;

    @Column(name = "provider_cost")
    private BigDecimal providerCost;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    public static SmsDelivery claimed(String dedupeKey, UUID eventId, NotificationKind kind,
                                      String phone) {
        SmsDelivery delivery = new SmsDelivery();
        delivery.dedupeKey = dedupeKey;
        delivery.eventId = eventId;
        delivery.kind = kind;
        delivery.phone = phone;
        delivery.status = DeliveryStatus.PENDING;
        delivery.createdAt = OffsetDateTime.now();
        delivery.updatedAt = delivery.createdAt;
        return delivery;
    }

    /** Whether a further attempt would be a second message rather than a first one. */
    public boolean isSettled() {
        return status != DeliveryStatus.PENDING;
    }
}
