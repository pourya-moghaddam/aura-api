package com.aura.catalog.size.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param categoryId null makes the size global. Otherwise it is offered only for that category and
 *                   everything beneath it.
 */
public record SizeRequest(
    @NotBlank(message = "Name is required")
    @Size(max = 50)
    String name,

    Long categoryId,

    Integer sortOrder,

    Boolean isActive
) {

    /** See {@code CategoryRequest} — a primitive here would make sort order silently mandatory. */
    public SizeRequest {
        if (sortOrder == null) {
            sortOrder = 0;
        }
    }
}
