package com.aura.order.order;

import java.util.Set;

/**
 * Where one seller's line stands.
 *
 * <p>Lives on the item rather than the order because a cart can hold several sellers' products and
 * each advances only their own (requirement 8). The order's own status is derived from these.
 *
 * <p>The transitions are declared here rather than checked at the call site, so there is exactly
 * one answer to "may this move to that" — and so a line cannot be walked backwards. A seller who
 * has marked something shipped cannot quietly un-ship it once the buyer has been told.
 */
public enum FulfillmentStatus {

    PENDING,
    PROCESSING,
    SHIPPED,
    DELIVERED,
    CANCELLED;

    /**
     * @return the statuses a seller may move this one to. Empty for the terminal ones — delivered
     *         and cancelled are the end of the line, and a correction to either is an
     *         administrative matter rather than something a seller does from their orders screen.
     */
    public Set<FulfillmentStatus> allowedNext() {
        return switch (this) {
            case PENDING -> Set.of(PROCESSING, CANCELLED);
            case PROCESSING -> Set.of(SHIPPED, CANCELLED);
            // No cancelling once it is with the courier: the goods have left, and cancelling would
            // release stock the shop no longer has.
            case SHIPPED -> Set.of(DELIVERED);
            case DELIVERED, CANCELLED -> Set.of();
        };
    }

    public boolean canMoveTo(FulfillmentStatus next) {
        return allowedNext().contains(next);
    }

    public boolean isTerminal() {
        return allowedNext().isEmpty();
    }
}
