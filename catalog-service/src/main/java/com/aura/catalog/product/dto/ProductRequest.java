package com.aura.catalog.product.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * @param categoryId must be a leaf. A seller picks the most specific category; the tree above it is
 *                   what makes the product findable from broader pages.
 * @param fieldValues the seller's answers to the fields their category inherits. Validated against
 *                    the resolved field set — every required field answered, every value on the
 *                    admin's list for its field.
 */
public record ProductRequest(
    @NotNull(message = "Category is required")
    Long categoryId,

    @NotBlank(message = "Name is required")
    @Size(max = 255)
    String name,

    @Size(max = 275)
    @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$",
        message = "Slug must be lowercase letters, digits and single hyphens")
    String slug,

    String description,

    @Valid
    List<ProductFieldValueRequest> fieldValues
) {

    public ProductRequest {
        if (fieldValues == null) {
            fieldValues = List.of();
        }
    }

    /**
     * @param valueIds the chosen values. More than one is only valid for a MULTI_SELECT field.
     */
    public record ProductFieldValueRequest(
        @NotNull(message = "Field is required")
        Long fieldId,

        @NotNull(message = "At least one value is required")
        List<Long> valueIds
    ) {
    }
}
