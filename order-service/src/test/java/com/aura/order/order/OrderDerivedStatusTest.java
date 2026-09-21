package com.aura.order.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The order-level status, derived from its items.
 *
 * <p>Requirement 8 puts fulfillment on the item because one basket can hold several sellers'
 * products and each seller advances only their own. The order as a whole is therefore only as far
 * along as its least-advanced item — and the interesting case is cancellation, where the obvious
 * "least advanced wins" rule gives the wrong answer.
 */
class OrderDerivedStatusTest {

    private OrderItem item(FulfillmentStatus status) {
        OrderItem item = OrderItem.of(1L, 1L, 1L, 1L, "P", Map.of(), 1000L, 1);
        item.setFulfillmentStatus(status);
        return item;
    }

    private FulfillmentStatus derive(FulfillmentStatus... statuses) {
        Order order = new Order();
        order.deriveStatusFrom(java.util.Arrays.stream(statuses).map(this::item).toList());
        return order.getDerivedStatus();
    }

    @Test
    @DisplayName("one seller still to start holds the whole order at pending")
    void leastAdvancedWins() {
        assertThat(derive(FulfillmentStatus.SHIPPED, FulfillmentStatus.PENDING))
            .isEqualTo(FulfillmentStatus.PENDING);
    }

    @Test
    @DisplayName("the order is delivered only when every item is")
    void deliveredNeedsEveryone() {
        assertThat(derive(FulfillmentStatus.DELIVERED, FulfillmentStatus.DELIVERED))
            .isEqualTo(FulfillmentStatus.DELIVERED);
        assertThat(derive(FulfillmentStatus.DELIVERED, FulfillmentStatus.SHIPPED))
            .isEqualTo(FulfillmentStatus.SHIPPED);
    }

    @Test
    @DisplayName("one seller cancelling does not make the whole order look cancelled")
    void oneCancellationIsIgnored() {
        // The buyer is still waiting on the rest. Showing "cancelled" would tell them their order
        // is off when most of it is on its way - and is the bug the naive minimum produces.
        assertThat(derive(FulfillmentStatus.CANCELLED, FulfillmentStatus.PROCESSING))
            .isEqualTo(FulfillmentStatus.PROCESSING);
    }

    @Test
    @DisplayName("every item cancelled does cancel the order")
    void allCancelledCancels() {
        assertThat(derive(FulfillmentStatus.CANCELLED, FulfillmentStatus.CANCELLED))
            .isEqualTo(FulfillmentStatus.CANCELLED);
    }

    @Test
    @DisplayName("an order with no items keeps the status it had")
    void emptyIsLeftAlone() {
        Order order = new Order();
        order.setDerivedStatus(FulfillmentStatus.PROCESSING);
        order.deriveStatusFrom(List.of());

        assertThat(order.getDerivedStatus()).isEqualTo(FulfillmentStatus.PROCESSING);
    }
}
