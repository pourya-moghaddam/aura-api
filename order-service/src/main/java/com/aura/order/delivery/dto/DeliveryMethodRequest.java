package com.aura.order.delivery.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * @param fee Rial, as an integer minor unit — never a decimal. Zero is allowed because free
 *            delivery is an ordinary offer, which is why this is {@code @PositiveOrZero} rather
 *            than {@code @Positive}.
 */
public record DeliveryMethodRequest(
    @NotBlank(message = "Name is required")
    @Size(max = 100)
    String name,

    String description,

    @NotNull(message = "Fee is required")
    @PositiveOrZero(message = "Fee cannot be negative")
    Long fee,

    Boolean isActive,

    Integer sortOrder
) {

    /** Boxed and defaulted — a primitive component would make these silently mandatory. */
    public DeliveryMethodRequest {
        if (isActive == null) {
            isActive = true;
        }
        if (sortOrder == null) {
            sortOrder = 0;
        }
    }
}
