package com.aura.catalog.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record CreateProductRequest(
    @NotNull Long categoryId,
    @NotBlank String name,
    @NotBlank String slug,
    String description,
    Map<String, Object> attributes
) {
}
