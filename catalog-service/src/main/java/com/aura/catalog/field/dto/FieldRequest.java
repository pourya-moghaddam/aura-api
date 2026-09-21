package com.aura.catalog.field.dto;

import com.aura.catalog.field.FieldDataType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * @param slug     optional. Derived from the name when omitted; a name with no Latin characters
 *                 leaves nothing to derive from, and the request is then rejected asking for one
 *                 explicitly rather than inventing a meaningless identifier that would live in
 *                 public filter URLs forever.
 * @param dataType defaults to SELECT, which with MULTI_SELECT is all the product form and the
 *                 search facets understand today.
 */
public record FieldRequest(
    @NotNull(message = "Category is required")
    Long categoryId,

    @NotBlank(message = "Name is required")
    @Size(max = 100)
    String name,

    @Size(max = 120)
    @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$",
        message = "Slug must be lowercase letters, digits and single hyphens")
    String slug,

    FieldDataType dataType,

    Boolean isRequired,

    Boolean isFilterable,

    Integer sortOrder
) {

    /** See {@code CategoryRequest} — primitives here would silently make these properties required. */
    public FieldRequest {
        if (dataType == null) {
            dataType = FieldDataType.SELECT;
        }
        if (isRequired == null) {
            isRequired = false;
        }
        if (isFilterable == null) {
            isFilterable = true;
        }
        if (sortOrder == null) {
            sortOrder = 0;
        }
    }
}
