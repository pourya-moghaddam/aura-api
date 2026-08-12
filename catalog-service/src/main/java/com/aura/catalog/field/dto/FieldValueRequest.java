package com.aura.catalog.field.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * @param slug optional, derived from the value when omitted. See {@code FieldRequest}.
 */
public record FieldValueRequest(
    @NotBlank(message = "Value is required")
    @Size(max = 150)
    String value,

    @Size(max = 170)
    @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$",
        message = "Slug must be lowercase letters, digits and single hyphens")
    String slug,

    Integer sortOrder
) {

    public FieldValueRequest {
        if (sortOrder == null) {
            sortOrder = 0;
        }
    }
}
