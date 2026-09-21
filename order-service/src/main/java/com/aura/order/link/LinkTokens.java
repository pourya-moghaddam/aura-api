package com.aura.order.link;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * The token in a seller's order link.
 *
 * <p>256 bits from {@link SecureRandom}, URL-safe, and only ever stored as a SHA-256 hash. It has
 * to be unguessable because it is the <em>only</em> credential protecting the order behind it —
 * the buyer has no account, and anyone with the link can pay.
 *
 * <p>SHA-256 rather than bcrypt, unlike a password. A 256-bit random token has no dictionary to
 * attack, so the slow hash buys nothing and would cost a lookup on every request. What matters is
 * only that the raw token cannot be recovered from the database.
 */
public final class LinkTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private LinkTokens() {
    }

    /** The raw token, returned to the seller exactly once and never stored. */
    public static String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 64 hex characters, which is what the column is sized for. */
    public static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JVM; if it is absent the platform is broken.
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
