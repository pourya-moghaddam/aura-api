package com.aura.order.cart.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * @param quantity zero removes the line — that is what a quantity box set to 0 means, and making
 *                 the shopper find a separate delete button for it would be worse.
 */
public record UpdateCartItemRequest(
    @NotNull(message = "Quantity is required")
    @PositiveOrZero(message = "Quantity cannot be negative")
    Integer quantity
) {
}
