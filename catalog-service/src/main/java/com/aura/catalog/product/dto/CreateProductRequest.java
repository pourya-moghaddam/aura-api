package com.aura.catalog.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.Map;

public record CreateProductRequest(
    @NotNull Long categoryId,
    @NotBlank String name,
    @NotBlank String slug,
    String description,
    @NotNull @Positive BigDecimal price,
    Map<String, Object> attributes
) {
}
