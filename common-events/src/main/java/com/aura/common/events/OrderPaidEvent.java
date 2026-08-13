package com.aura.common.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Emitted when a payment is verified and an order is genuinely paid for.
 *
 * <p>The signal search needs to know what is selling. Deliberately tied to <em>payment</em> rather
 * than to checkout: an order that was created and abandoned at the gateway says nothing about
 * demand, and counting it would let anyone inflate a product's popularity by filling a basket.
 *
 * <p>Carries the lines rather than an order id, for the same reason ProductChanged carries the
 * whole document: a consumer with an id has to call back into order-service, which puts a network
 * dependency on the counting of something that is only an approximation anyway.
 */
public record OrderPaidEvent(
    UUID eventId,
    Instant occurredAt,
    Long orderId,
    String traceCode,
    List<Line> lines
) implements DomainEvent {

    /**
     * @param quantity how many were bought, so a single order of ten counts as ten rather than one
     */
    public record Line(Long productId, Long variantId, Integer quantity) {
    }

    /** Keyed by order, so a retry of one order cannot interleave with itself. */
    public String partitionKey() {
        return String.valueOf(orderId);
    }
}
