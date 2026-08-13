package com.aura.order.cart.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AddCartItemRequest(
    @NotNull(message = "Variant is required")
    Long variantId,

    // Boxed and defaulted: a primitive would make this silently mandatory and turn its absence
    // into an opaque 400 naming no field.
    @Positive(message = "Quantity must be at least one")
    Integer quantity
) {

    public AddCartItemRequest {
        if (quantity == null) {
            quantity = 1;
        }
    }
}
