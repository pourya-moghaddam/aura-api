package com.aura.auth.token;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.time.Duration;
import java.time.Instant;

/**
 * Reads and writes the refresh token cookie.
 *
 * <p>httpOnly so client-side JavaScript cannot read it — the one thing that matters if the
 * storefront ever renders anything user-supplied, since it closes off the main payoff of an XSS
 * against this app. Not {@code Secure} unconditionally: local development runs over plain HTTP,
 * and browsers silently drop {@code Secure} cookies on that origin rather than erroring, which
 * would otherwise make refresh silently not work with no indication why.
 *
 * <p>Scoped to {@code /api/auth}, not site-wide — the access token never needs this cookie, so
 * there is no reason to attach it to every request. It has to cover both {@code /token/refresh}
 * (which reads it) and {@code /users/logout} (which reads and clears it); scoping it to just the
 * former, as an earlier version of this class did, means the browser silently never sends it to
 * the latter and logout can neither find nor clear it.
 */
public final class RefreshTokenCookie {

    public static final String NAME = "aura_refresh_token";
    static final String PATH = "/api/auth";

    private RefreshTokenCookie() {
    }

    public static void set(HttpServletResponse response, String rawToken, Instant expiresAt, boolean secure) {
        long maxAgeSeconds = Math.max(0, Duration.between(Instant.now(), expiresAt).toSeconds());
        response.addHeader("Set-Cookie", cookieHeader(rawToken, maxAgeSeconds, secure));
    }

    /** {@code Max-Age=0} deletes the cookie in every browser that receives this response. */
    public static void clear(HttpServletResponse response, boolean secure) {
        response.addHeader("Set-Cookie", cookieHeader("", 0, secure));
    }

    public static String readOrThrow(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (NAME.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        throw new MissingRefreshTokenException();
    }

    /**
     * Built by hand rather than via {@link jakarta.servlet.http.Cookie} + {@code response.addCookie},
     * because that API has no way to set {@code SameSite} at all.
     */
    private static String cookieHeader(String value, long maxAgeSeconds, boolean secure) {
        StringBuilder header = new StringBuilder()
            .append(NAME).append('=').append(value)
            .append("; Path=").append(PATH)
            .append("; Max-Age=").append(maxAgeSeconds)
            .append("; HttpOnly")
            .append("; SameSite=Lax");
        if (secure) {
            header.append("; Secure");
        }
        return header.toString();
    }
}
