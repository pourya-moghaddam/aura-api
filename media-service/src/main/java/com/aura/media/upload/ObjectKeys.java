package com.aura.media.upload;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Builds object keys.
 *
 * <p>Date-prefixed ({@code 2026/08/08/<uuid>}) rather than flat. Object stores partition by key
 * prefix, so a flat namespace concentrates every write on one partition; a date prefix spreads
 * them. It also makes lifecycle rules and manual archaeology ("what did we take in last Tuesday")
 * possible without a database round trip.
 *
 * <p>No user-supplied filename appears in the key. Original names arrive with path separators,
 * unicode, and collisions in them, and a key built from one is a path-traversal question waiting to
 * be asked. The name is kept in the database for display and nowhere near the key.
 */
public final class ObjectKeys {

    private ObjectKeys() {
    }

    public static String forUpload(UUID mediaId, LocalDate date) {
        return "%04d/%02d/%02d/%s".formatted(
            date.getYear(), date.getMonthValue(), date.getDayOfMonth(), mediaId);
    }

    public static String forVariant(String baseKey, String variant) {
        return baseKey + "/" + variant;
    }
}
