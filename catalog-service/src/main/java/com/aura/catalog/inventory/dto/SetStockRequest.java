package com.aura.catalog.inventory.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * @param quantityOnHand the absolute figure, not a delta. A seller counting a shelf knows how many
 *                       there are, not how many have changed since they last looked, and a delta
 *                       applied twice by a double-clicked form is silently wrong.
 */
public record SetStockRequest(
    @NotNull(message = "Quantity is required")
    @PositiveOrZero(message = "Quantity cannot be negative")
    Integer quantityOnHand
) {
}
