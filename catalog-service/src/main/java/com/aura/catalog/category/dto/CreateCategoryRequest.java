package com.aura.catalog.category.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateCategoryRequest(
    @NotBlank String name,
    @NotBlank String slug,
    Long parentId
) {
}
