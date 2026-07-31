package com.aura.catalog.product.dto;

import java.math.BigDecimal;
import java.util.Map;

public record ProductResponse(
    Long id,
    Long categoryId,
    String categoryName,
    String name,
    String slug,
    String description,
    BigDecimal price,
    boolean isActive,
    Map<String, Object> attributes
) {
}
