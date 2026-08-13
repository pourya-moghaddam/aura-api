package com.aura.order.cart;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.time.Duration;
import java.util.UUID;

/**
 * The guest's claim on their cart.
 *
 * <p>This token <em>is</em> the authentication for an anonymous basket, which is why it is a
 * random UUID rather than anything derived or sequential: guessing one hands over someone else's
 * cart. httpOnly for the same reason the refresh token is — an XSS should not be able to read it.
 *
 * <p>Path is the whole site, unlike the refresh cookie's {@code /api/auth}. A cart is touched from
 * product pages, the cart page and checkout, and scoping it narrowly would mean the browser
 * silently omits it on exactly the requests that need it.
 *
 * <p>Long-lived on purpose. A shopper returning a fortnight later expects their basket to still be
 * there; the sweep that eventually reclaims abandoned carts is generous for the same reason.
 */
public final class CartTokenCookie {

    public static final String NAME = "aura_cart_token";

    private static final String PATH = "/";
    private static final Duration MAX_AGE = Duration.ofDays(30);

    private CartTokenCookie() {
    }

    /**
     * Reads the token, or empty when there is none — which simply means this is a first visit, not
     * an error.
     */
    public static java.util.Optional<UUID> read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return java.util.Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (NAME.equals(cookie.getName())) {
                try {
                    return java.util.Optional.of(UUID.fromString(cookie.getValue()));
                } catch (IllegalArgumentException e) {
                    // A malformed cookie is treated as absent rather than as an error: the shopper
                    // gets a fresh cart instead of a 400 they can do nothing about.
                    return java.util.Optional.empty();
                }
            }
        }
        return java.util.Optional.empty();
    }

    public static void set(HttpServletResponse response, UUID token, boolean secure) {
        response.addHeader("Set-Cookie", header(token.toString(), MAX_AGE.toSeconds(), secure));
    }

    /** Cleared once a guest cart has been merged into an account's, so it cannot be reused. */
    public static void clear(HttpServletResponse response, boolean secure) {
        response.addHeader("Set-Cookie", header("", 0, secure));
    }

    /**
     * Built by hand rather than with {@link Cookie}, which has no way to set {@code SameSite} —
     * the same reason the refresh-token cookie is written this way.
     */
    private static String header(String value, long maxAgeSeconds, boolean secure) {
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
