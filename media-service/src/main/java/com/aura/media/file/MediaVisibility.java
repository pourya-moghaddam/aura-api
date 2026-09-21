package com.aura.media.file;

/**
 * Who may read a file, as distinct from whether it is readable at all.
 *
 * <p>{@link MediaStatus} answers "have these bytes been proven safe to serve"; this answers "and to
 * whom". Both gates apply to every read, and they are deliberately separate: a quarantined file
 * that someone marked public must still not be servable, and a private file that passed scanning
 * must still be owner-only.
 *
 * <p>Defaults to {@link #PRIVATE}. Publishing is an explicit act performed when a file is attached
 * to something the public can already see — a product, a banner — so an uploaded-but-unattached
 * file is never reachable by guessing its id. Defaulting the other way would mean every draft
 * image a seller ever uploaded was world-readable from the moment it finished scanning.
 */
public enum MediaVisibility {

    /** Owner-only. The default, and the state every upload starts in. */
    PRIVATE,

    /**
     * Anonymously readable through {@code GET /api/media/{id}/content}.
     *
     * <p>Set when catalog-service attaches the file to a product, because at that point the image
     * is about to appear on a storefront page that needs no credentials to view. Marking it public
     * is what makes the storefront's rendering path work without handing shoppers a token.
     */
    PUBLIC;

    public boolean isPublic() {
        return this == PUBLIC;
    }
}
