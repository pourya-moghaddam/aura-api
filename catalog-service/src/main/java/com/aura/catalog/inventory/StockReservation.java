package com.aura.catalog.inventory;

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
 * A hold on stock while an order waits to be paid for.
 *
 * <p>This record is what replaces a distributed saga. Order-service reserves, sends the shopper to
 * the payment gateway, and then either commits or releases — and if it never comes back at all,
 * the expiry sweep releases the hold anyway. The stock returns without anyone having written a
 * compensating transaction, because the hold was always temporary by construction.
 */
@Entity
@Table(name = "stock_reservations")
@Getter
@Setter
@NoArgsConstructor
public class StockReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "variant_id", nullable = false)
    private Long variantId;

    /** Order-service's id. No foreign key — separate service, separate database. */
    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status = ReservationStatus.HELD;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "settled_at")
    private OffsetDateTime settledAt;

    public static StockReservation hold(long variantId, long orderId, int quantity,
                                        OffsetDateTime expiresAt) {
        StockReservation reservation = new StockReservation();
        reservation.variantId = variantId;
        reservation.orderId = orderId;
        reservation.quantity = quantity;
        reservation.status = ReservationStatus.HELD;
        reservation.expiresAt = expiresAt;
        reservation.createdAt = OffsetDateTime.now();
        return reservation;
    }

    public void settle(ReservationStatus outcome) {
        this.status = outcome;
        this.settledAt = OffsetDateTime.now();
    }

    /** Mirrors the {@code ck_reservation_status} check constraint. */
    public enum ReservationStatus {

        /** Stock is being held. Counts towards {@code quantity_reserved}. */
        HELD,

        /** Paid for. The stock has left {@code quantity_on_hand} for good. */
        COMMITTED,

        /** Abandoned, failed, or expired. The stock went back on the shelf. */
        RELEASED;

        public boolean isSettled() {
            return this != HELD;
        }
    }
}
