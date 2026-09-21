package com.aura.catalog.inventory.dto;

import com.aura.catalog.inventory.StockReservation;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * @param expiresAt when the hold lapses if the order is not paid for. order-service shows this as
 *                  the checkout countdown, so it is returned rather than assumed.
 */
public record ReservationResponse(
    Long orderId,
    List<Held> held,
    OffsetDateTime expiresAt
) {

    public record Held(Long reservationId, Long variantId, int quantity) {

        public static Held from(StockReservation reservation) {
            return new Held(reservation.getId(), reservation.getVariantId(), reservation.getQuantity());
        }
    }
}
