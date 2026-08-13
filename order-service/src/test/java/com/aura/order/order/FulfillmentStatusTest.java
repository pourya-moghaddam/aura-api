package com.aura.order.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a seller may do to one of their lines.
 *
 * <p>These are the rules that stop a line being walked backwards after the buyer has been told
 * about it. Declared on the enum rather than checked at the call site so there is exactly one
 * answer, and pinned here because the cost of getting one wrong is a customer receiving "your
 * order has shipped" twice, or after "delivered".
 */
class FulfillmentStatusTest {

    @Test
    @DisplayName("the ordinary path runs forwards one step at a time")
    void forwardPath() {
        assertThat(FulfillmentStatus.PENDING.canMoveTo(FulfillmentStatus.PROCESSING)).isTrue();
        assertThat(FulfillmentStatus.PROCESSING.canMoveTo(FulfillmentStatus.SHIPPED)).isTrue();
        assertThat(FulfillmentStatus.SHIPPED.canMoveTo(FulfillmentStatus.DELIVERED)).isTrue();
    }

    @Test
    @DisplayName("steps cannot be skipped")
    void noSkipping() {
        // "Delivered" straight from pending would mean no dispatch notice was ever sent.
        assertThat(FulfillmentStatus.PENDING.canMoveTo(FulfillmentStatus.SHIPPED)).isFalse();
        assertThat(FulfillmentStatus.PENDING.canMoveTo(FulfillmentStatus.DELIVERED)).isFalse();
        assertThat(FulfillmentStatus.PROCESSING.canMoveTo(FulfillmentStatus.DELIVERED)).isFalse();
    }

    @Test
    @DisplayName("nothing goes backwards")
    void noReversing() {
        assertThat(FulfillmentStatus.SHIPPED.canMoveTo(FulfillmentStatus.PROCESSING)).isFalse();
        assertThat(FulfillmentStatus.SHIPPED.canMoveTo(FulfillmentStatus.PENDING)).isFalse();
        assertThat(FulfillmentStatus.DELIVERED.canMoveTo(FulfillmentStatus.SHIPPED)).isFalse();
    }

    @Test
    @DisplayName("a line can be cancelled until it is with the courier")
    void cancellationWindow() {
        // After dispatch the goods have left. Cancelling then would release stock the shop no
        // longer has, and the parcel would still arrive.
        assertThat(FulfillmentStatus.PENDING.canMoveTo(FulfillmentStatus.CANCELLED)).isTrue();
        assertThat(FulfillmentStatus.PROCESSING.canMoveTo(FulfillmentStatus.CANCELLED)).isTrue();
        assertThat(FulfillmentStatus.SHIPPED.canMoveTo(FulfillmentStatus.CANCELLED)).isFalse();
    }

    @Test
    @DisplayName("delivered and cancelled are the end of the line")
    void terminals() {
        assertThat(FulfillmentStatus.DELIVERED.isTerminal()).isTrue();
        assertThat(FulfillmentStatus.CANCELLED.isTerminal()).isTrue();
        assertThat(FulfillmentStatus.PENDING.isTerminal()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(FulfillmentStatus.class)
    @DisplayName("no status is a legal move to itself")
    void selfTransitionsAreNotMoves(FulfillmentStatus status) {
        // Handled as idempotent by the service rather than as a transition, so that a
        // double-clicked button is not an error. It must not be legal here, or a repeated click
        // would emit a second notification.
        assertThat(status.canMoveTo(status)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(FulfillmentStatus.class)
    @DisplayName("every declared next step is one the enum agrees with")
    void allowedNextAgreesWithCanMoveTo(FulfillmentStatus status) {
        for (FulfillmentStatus next : status.allowedNext()) {
            assertThat(status.canMoveTo(next)).isTrue();
        }
    }
}
