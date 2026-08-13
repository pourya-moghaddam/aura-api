package com.aura.order.delivery.dto;

import com.aura.order.delivery.DeliveryMethod;

public record DeliveryMethodResponse(
    Long id,
    String name,
    String description,
    Long fee,
    boolean isActive,
    int sortOrder
) {

    public static DeliveryMethodResponse from(DeliveryMethod method) {
        return new DeliveryMethodResponse(method.getId(), method.getName(), method.getDescription(),
            method.getFee(), method.isActive(), method.getSortOrder());
    }
}
