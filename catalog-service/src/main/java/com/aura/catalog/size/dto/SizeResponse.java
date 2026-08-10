package com.aura.catalog.size.dto;

import com.aura.catalog.size.Size;

public record SizeResponse(
    Long id,
    String name,
    Long categoryId,
    int sortOrder,
    boolean isActive
) {

    public static SizeResponse from(Size size) {
        return new SizeResponse(size.getId(), size.getName(), size.getCategoryId(),
            size.getSortOrder(), size.isActive());
    }
}
