package com.aura.order.discount.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Just the code. The subtotal it is quoted against comes from the caller's own cart on the server,
 * never from the request — a client-supplied total would let anyone clear a minimum-order
 * threshold by claiming a larger basket than they have.
 */
public record DiscountQuoteRequest(
    @NotBlank(message = "Code is required")
    @Size(max = 50)
    String code
) {
}
