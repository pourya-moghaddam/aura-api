package com.aura.catalog.support;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Derives URL-safe slugs from display names.
 *
 * <p>Deliberately does not transliterate. A store whose catalogue is largely Persian will produce
 * an empty slug for most names, and the honest response is to say so and make the admin supply one
 * — see {@link #deriveOrNull}. The alternatives are worse: transliteration schemes for Persian
 * disagree with each other, and auto-generating {@code field-17} puts a meaningless number in a
 * public filter URL that is then permanent.
 */
public final class Slugs {

    private Slugs() {
    }

    /**
     * Returns a slug derived from {@code text}, or null when nothing usable survives — which is the
     * normal outcome for non-Latin input rather than an error condition.
     */
    public static String deriveOrNull(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        // Decompose accents so "Café" yields "cafe" rather than losing the final letter entirely.
        String slug = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "")
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+|-+$", "");

        return slug.isEmpty() ? null : slug;
    }
}
