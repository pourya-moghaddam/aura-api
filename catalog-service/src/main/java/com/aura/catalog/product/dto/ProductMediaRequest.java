package com.aura.catalog.product.dto;

import com.aura.catalog.product.ProductMedia;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * @param mediaId  an id from media-service. Checked to be servable and owned by this seller before
 *                 it is attached.
 * @param isPrimary the listing image. Setting a new one demotes the previous.
 */
public record ProductMediaRequest(
    @NotNull(message = "Media id is required")
    UUID mediaId,

    ProductMedia.MediaKind kind,

    Integer sortOrder,

    Boolean isPrimary
) {

    public ProductMediaRequest {
        if (kind == null) {
            kind = ProductMedia.MediaKind.IMAGE;
        }
        if (sortOrder == null) {
            sortOrder = 0;
        }
        if (isPrimary == null) {
            isPrimary = false;
        }
    }
}
