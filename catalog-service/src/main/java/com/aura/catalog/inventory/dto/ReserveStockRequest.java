package com.aura.catalog.inventory.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

/**
 * @param orderId order-service's id. Also the idempotency key: reserving twice for the same order
 *                returns the existing hold rather than taking the stock a second time, because a
 *                retried checkout must not consume double.
 */
public record ReserveStockRequest(
    @NotNull(message = "Order id is required")
    Long orderId,

    @NotEmpty(message = "At least one line is required")
    @Valid
    List<Line> lines
) {

    public record Line(
        @NotNull(message = "Variant id is required")
        Long variantId,

        @NotNull(message = "Quantity is required")
        @Positive(message = "Quantity must be greater than zero")
        Integer quantity
    ) {
    }
}
