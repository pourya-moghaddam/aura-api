package com.aura.catalog.category.dto;

public record CategoryResponse(
    Long id,
    String name,
    String slug,
    Long parentId
) {
}
