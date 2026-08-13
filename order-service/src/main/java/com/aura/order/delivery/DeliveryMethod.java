package com.aura.order.delivery;

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
 * A shipping option and what it costs.
 *
 * <p>Flat fee, by decision — no destination or weight based pricing in the first release, so
 * checkout reads a number rather than requesting a quote from a courier.
 *
 * <p>Lives in order-service rather than catalog, where every other admin-managed lookup table sits.
 * Ownership follows the transactional reader: checkout prices delivery inside its own transaction,
 * and calling another service on every checkout would be slower and a new way for checkout to fail.
 * The control panel being one screen does not mean it talks to one service.
 */
@Entity
@Table(name = "delivery_methods")
@Getter
@Setter
@NoArgsConstructor
public class DeliveryMethod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** Rial, as an integer minor unit. Zero is legitimate — free delivery is a normal offer. */
    @Column(nullable = false)
    private Long fee;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public static DeliveryMethod of(String name, String description, Long fee, int sortOrder) {
        DeliveryMethod method = new DeliveryMethod();
        method.name = name;
        method.description = description;
        method.fee = fee;
        method.sortOrder = sortOrder;
        method.createdAt = OffsetDateTime.now();
        method.updatedAt = method.createdAt;
        return method;
    }

    public void touch() {
        this.updatedAt = OffsetDateTime.now();
    }
}
