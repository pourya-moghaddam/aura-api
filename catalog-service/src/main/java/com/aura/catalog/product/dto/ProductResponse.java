package com.aura.catalog.product.dto;

import java.util.Map;

public record ProductResponse(
    Long id,
    Long categoryId,
    String categoryName,
    String name,
    String slug,
    String description,
    boolean isActive,
    Map<String, Object> attributes
) {
}
