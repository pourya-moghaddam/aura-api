package com.aura.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Emitted whenever a seller moves one of their order lines along.
 *
 * <p>Per <em>item</em>, not per order, because requirement 8 gives each seller control of their own
 * lines and one basket can hold several sellers' products. A buyer whose order is split between two
 * sellers hears about each dispatch as it happens, which is what they actually want to know.
 *
 * <p>Carries the buyer's phone and the order's trace code so notification-service can send the SMS
 * without calling back — it has no access to the orders database, and an id-only event would make
 * every notification depend on order-service being up.
 *
 * @param previousStatus what it moved from, so a consumer can tell a first dispatch from a
 *                       correction and decide whether the message is worth sending at all
 * @param orderStatus    the order's status after this change, derived from all its items. Lets a
 *                       consumer say "your order is on its way" only when every seller has sent.
 */
public record OrderItemStatusChangedEvent(
    UUID eventId,
    Instant occurredAt,
    Long orderId,
    Long orderItemId,
    String traceCode,
    Long sellerId,
    Long productId,
    Long variantId,
    String productName,
    Integer quantity,
    String previousStatus,
    String newStatus,
    String orderStatus,
    String buyerPhone,
    String buyerName,
    Long userId
) implements DomainEvent {

    /**
     * Partition key. Every event for one order lands on one partition, so a buyer's notifications
     * about that order arrive in the sequence they happened rather than interleaved across
     * partitions — "delivered" before "shipped" is a confusing message to receive.
     */
    public String partitionKey() {
        return String.valueOf(orderId);
    }
}
