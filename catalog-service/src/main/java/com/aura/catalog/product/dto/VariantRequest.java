package com.aura.catalog.product.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * @param colorId  null when the product has no colour axis at all
 * @param sizeId   null when it has no size axis
 * @param sku      optional. Generated from the product and combination when omitted, because most
 *                 sellers have no SKU scheme and an auto-generated one still has to be unique.
 * @param price    Rial, as an integer minor unit. Never a decimal.
 * @param compareAtPrice optional "was" price for showing a discount; must exceed {@code price}, and
 *                       is never what the customer is charged.
 */
public record VariantRequest(
    Long colorId,

    Long sizeId,

    @Size(max = 100)
    String sku,

    @NotNull(message = "Price is required")
    @Positive(message = "Price must be greater than zero")
    Long price,

    @Positive(message = "Compare-at price must be greater than zero")
    Long compareAtPrice,

    Boolean isActive
) {

    public VariantRequest {
        if (isActive == null) {
            isActive = true;
        }
    }
}
