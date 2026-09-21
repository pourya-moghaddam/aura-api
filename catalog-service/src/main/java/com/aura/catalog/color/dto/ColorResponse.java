package com.aura.catalog.color.dto;

import com.aura.catalog.color.Color;

public record ColorResponse(
    Long id,
    String name,
    String hexCode,
    int sortOrder,
    boolean isActive
) {

    public static ColorResponse from(Color color) {
        return new ColorResponse(color.getId(), color.getName(), color.getHexCode(),
            color.getSortOrder(), color.isActive());
    }
}
