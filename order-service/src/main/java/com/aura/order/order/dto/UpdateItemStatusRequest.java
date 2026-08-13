package com.aura.order.order.dto;

import com.aura.order.order.FulfillmentStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateItemStatusRequest(
    @NotNull(message = "A status is required")
    FulfillmentStatus status
) {
}
