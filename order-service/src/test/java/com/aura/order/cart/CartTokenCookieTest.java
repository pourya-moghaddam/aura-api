package com.aura.order.cart;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The guest's claim on their basket.
 *
 * <p>This token <em>is</em> the authentication for an anonymous cart — there is no account behind
 * it and no password — so the cookie's attributes are the whole of its protection. Each assertion
 * below corresponds to one whose absence is silent in testing and costly in production.
 */
class CartTokenCookieTest {

    private String setCookieHeader(boolean secure) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        CartTokenCookie.set(response, UUID.randomUUID(), secure);
        return response.getHeader("Set-Cookie");
    }

    @Test
    @DisplayName("httpOnly, so an XSS cannot read someone's cart token")
    void httpOnly() {
        assertThat(setCookieHeader(false)).contains("HttpOnly");
    }

    @Test
    @DisplayName("SameSite=Lax")
    void sameSiteLax() {
        assertThat(setCookieHeader(false)).contains("SameSite=Lax");
    }

    @Test
    @DisplayName("scoped to the whole site, not to one path")
    void pathIsSiteWide() {
        // Unlike the refresh cookie's /api/auth. A cart is touched from product pages, the cart
        // page and checkout; scoping it narrowly means the browser silently omits it on exactly
        // the requests that need it, and the basket appears to empty itself.
        assertThat(setCookieHeader(false)).contains("Path=/");
    }

    @Test
    @DisplayName("Secure is set when asked and omitted for plain-HTTP development")
    void secureIsConditional() {
        // Browsers drop Secure cookies on http origins without saying so, which would look like
        // carts simply not persisting locally.
        assertThat(setCookieHeader(true)).contains("; Secure");
        assertThat(setCookieHeader(false)).doesNotContain("; Secure");
    }

    @Test
    @DisplayName("long-lived, because a shopper returning a fortnight later expects their basket")
    void longLived() {
        assertThat(setCookieHeader(false)).contains("Max-Age=2592000");
    }

    @Test
    @DisplayName("the token is read back out of the request")
    void readsTheToken() {
        UUID token = UUID.randomUUID();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("other", "x"),
            new Cookie(CartTokenCookie.NAME, token.toString()));

        assertThat(CartTokenCookie.read(request)).contains(token);
    }

    @Test
    @DisplayName("no cookie means a first visit, not an error")
    void absentCookieIsEmpty() {
        assertThat(CartTokenCookie.read(new MockHttpServletRequest())).isEmpty();
    }

    @Test
    @DisplayName("other cookies but not this one is also just absent")
    void otherCookiesOnly() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("session", "x"));

        assertThat(CartTokenCookie.read(request)).isEmpty();
    }

    @Test
    @DisplayName("a malformed token is treated as absent rather than as a 400")
    void malformedTokenIsEmpty() {
        // The shopper gets a fresh cart instead of an error they can do nothing about - they
        // cannot edit their own cookie back into shape.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(CartTokenCookie.NAME, "not-a-uuid"));

        assertThat(CartTokenCookie.read(request)).isEmpty();
    }

    @Test
    @DisplayName("clearing expires the cookie on the same path it was set")
    void clearDeletesTheCookie() {
        // The path has to match, or the browser keeps the original and the stale token can be
        // presented for a basket that now belongs to an account.
        MockHttpServletResponse response = new MockHttpServletResponse();
        CartTokenCookie.clear(response, false);

        assertThat(response.getHeader("Set-Cookie"))
            .contains("aura_cart_token=;")
            .contains("Max-Age=0")
            .contains("Path=/");
    }
}
