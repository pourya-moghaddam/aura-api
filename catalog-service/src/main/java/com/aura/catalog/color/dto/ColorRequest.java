package com.aura.catalog.color.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * @param hexCode what the colour picker sends. Validated here as well as by a database CHECK: the
 *                constraint is the guarantee, this is what makes a bad value a readable 400 naming
 *                the field instead of a 500 from a constraint violation.
 */
public record ColorRequest(
    @NotBlank(message = "Name is required")
    @Size(max = 50)
    String name,

    @NotBlank(message = "Hex code is required")
    @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "Hex code must be in the form #RRGGBB")
    String hexCode,

    Integer sortOrder,

    Boolean isActive
) {

    /** See {@code CategoryRequest} — a primitive here would make sort order silently mandatory. */
    public ColorRequest {
        if (sortOrder == null) {
            sortOrder = 0;
        }
    }
}
