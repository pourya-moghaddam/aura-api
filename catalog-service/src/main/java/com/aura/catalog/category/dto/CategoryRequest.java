package com.aura.catalog.category.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * @param parentId null makes this a root category
 * @param slug     URL segment, globally unique. Restricted to lowercase, digits and hyphens —
 *                 category URLs are public and permanent, and a slug with spaces or unicode in it
 *                 is a source of encoding bugs forever after.
 */
public record CategoryRequest(
    Long parentId,

    @NotBlank(message = "Name is required")
    @Size(max = 100)
    String name,

    @NotBlank(message = "Slug is required")
    @Size(max = 120)
    @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$",
        message = "Slug must be lowercase letters, digits and single hyphens")
    String slug,

    int sortOrder,

    Boolean isActive
) {
}
