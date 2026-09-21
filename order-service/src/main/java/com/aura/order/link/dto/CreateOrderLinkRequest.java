package com.aura.order.link.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * An order a seller composes for a buyer who is not on the site — requirement 1.
 *
 * <p>The buyer's details come from the seller because the buyer is typically on the telephone.
 * Prices do not: they are read from catalog when the order is written, so a seller cannot set
 * their own figure here.
 */
public record CreateOrderLinkRequest(
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

    @NotEmpty(message = "At least one item is required")
    @Valid
    List<Line> items
) {

    public record Line(
        @NotNull(message = "Variant is required")
        Long variantId,

        @NotNull(message = "Quantity is required")
        @Positive(message = "Quantity must be at least one")
        Integer quantity
    ) {
    }
}
