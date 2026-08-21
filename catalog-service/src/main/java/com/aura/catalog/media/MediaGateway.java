package com.aura.catalog.media;

import java.util.UUID;

/**
 * What catalog needs to know about a file it is about to reference.
 *
 * <p>An interface so the product rules can be tested without a media-service on the other end, and
 * so the current synchronous implementation can be replaced by an event-fed local projection later
 * without touching anything that calls it.
 */
public interface MediaGateway {

    /**
     * @return whether the file exists, belongs to the calling seller, and has passed validation and
     *         scanning.
     */
    boolean isUsableBy(UUID mediaId);

    /**
     * Makes the file readable by anonymous visitors.
     *
     * <p>Called when media is attached to a product, because that is the moment the image acquires
     * a public audience: a storefront shopper carries no token, so an owner-scoped file cannot be
     * rendered on a product page. Until this runs, media-service serves the file to nobody but its
     * owner.
     */
    void publish(UUID mediaId);
}
