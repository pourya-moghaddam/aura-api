package com.aura.order.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Everything checkout needs that is not already in the cart.
 *
 * <p>The address arrives in full rather than as a reference to the buyer's address book. A guest
 * has no address book at all (requirement 12), and for everyone else the order stores a snapshot
 * anyway — editing a saved address must never rewrite where a past order was sent.
 *
 * @param idempotencyKey the client's key for this checkout. Optional but strongly advised: without
 *                       one, a browser retrying a timed-out POST places a second order.
 */
public record CheckoutRequest(
    @NotBlank(message = "First name is required")
    @Size(max = 100)
    String buyerFirstName,

    @NotBlank(message = "Last name is required")
    @Size(max = 100)
    String buyerLastName,

    @NotBlank(message = "Phone number is required")
    String buyerPhone,

    @NotBlank(message = "Province is required")
    @Size(max = 100)
    String province,

    @NotBlank(message = "City is required")
    @Size(max = 100)
    String city,

    @NotBlank(message = "Address is required")
    @Size(max = 500)
    String addressLine,

    @NotBlank(message = "Postal code is required")
    @Pattern(regexp = "\\d{10}", message = "A postal code is ten digits")
    String postalCode,

    @NotNull(message = "Delivery method is required")
    Long deliveryMethodId,

    /** Optional. Validated and spent under a lock as the order is written. */
    @Size(max = 50)
    String discountCode,

    @Size(max = 100)
    String idempotencyKey
) {
}
