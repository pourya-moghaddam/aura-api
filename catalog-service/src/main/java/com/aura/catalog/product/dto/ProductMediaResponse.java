package com.aura.catalog.product.dto;

import com.aura.catalog.product.ProductMedia;

import java.util.UUID;

/**
 * Carries the media id, not a URL. Download URLs are short-lived and minted by media-service after
 * its own permission check; embedding one here would either expire in the client's hands or
 * outlive the check that produced it.
 */
public record ProductMediaResponse(
    Long id,
    UUID mediaId,
    ProductMedia.MediaKind kind,
    int sortOrder,
    boolean isPrimary
) {

    public static ProductMediaResponse from(ProductMedia media) {
        return new ProductMediaResponse(media.getId(), media.getMediaId(), media.getKind(),
            media.getSortOrder(), media.isPrimary());
    }
}
