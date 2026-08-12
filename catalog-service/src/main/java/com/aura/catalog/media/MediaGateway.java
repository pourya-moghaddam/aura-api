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
}
