package com.aura.auth.token;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.http.Cookie;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The refresh token is a bearer credential with a thirty-day life, so the cookie attributes are
 * the whole of its protection. Every assertion here corresponds to an attribute whose absence is
 * invisible in testing and expensive in production.
 */
class RefreshTokenCookieTest {

    private String setCookieHeader(boolean secure) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        RefreshTokenCookie.set(response, "raw-token", Instant.now().plus(30, ChronoUnit.DAYS), secure);
        return response.getHeader("Set-Cookie");
    }

    @Test
    @DisplayName("the cookie is httpOnly, so an XSS cannot read the refresh token")
    void httpOnly() {
        assertThat(setCookieHeader(false)).contains("HttpOnly");
    }

    @Test
    @DisplayName("the cookie is SameSite=Lax")
    void sameSiteLax() {
        assertThat(setCookieHeader(false)).contains("SameSite=Lax");
    }

    @Test
    @DisplayName("the path covers both refresh and logout, not just refresh")
    void pathCoversLogout() {
        // Scoping this to /api/auth/token/refresh means the browser never sends it to the logout
        // endpoint, which then cannot find the cookie to revoke or clear it - the user appears to
        // log out while their refresh family stays alive.
        assertThat(setCookieHeader(false)).contains("Path=/api/auth");
    }

    @Test
    @DisplayName("Secure is set when asked and omitted in plain-HTTP development")
    void secureIsConditional() {
        // Browsers silently drop Secure cookies on http://localhost rather than reporting an
        // error, so forcing it on would make local refresh fail with no visible cause.
        assertThat(setCookieHeader(true)).contains("; Secure");
        assertThat(setCookieHeader(false)).doesNotContain("; Secure");
    }

    @Test
    @DisplayName("Max-Age tracks the token's actual expiry")
    void maxAgeFollowsExpiry() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        RefreshTokenCookie.set(response, "raw", Instant.now().plus(1, ChronoUnit.HOURS), false);

        String header = response.getHeader("Set-Cookie");
        assertThat(header).containsPattern("Max-Age=3[0-9]{3}");
    }

    @Test
    @DisplayName("an already-expired token yields Max-Age=0 rather than a negative number")
    void expiredTokenClampsToZero() {
        // A negative Max-Age is not a "delete now" instruction; it is malformed, and browsers
        // disagree about what to do with it.
        MockHttpServletResponse response = new MockHttpServletResponse();
        RefreshTokenCookie.set(response, "raw", Instant.now().minus(1, ChronoUnit.HOURS), false);

        assertThat(response.getHeader("Set-Cookie")).contains("Max-Age=0");
    }

    @Test
    @DisplayName("clearing sets an empty value with Max-Age=0 on the same path")
    void clearDeletesTheCookie() {
        // The path has to match the one used to set it, or the browser keeps the original.
        MockHttpServletResponse response = new MockHttpServletResponse();
        RefreshTokenCookie.clear(response, false);

        String header = response.getHeader("Set-Cookie");
        assertThat(header).contains("aura_refresh_token=;").contains("Max-Age=0").contains("Path=/api/auth");
    }

    @Test
    @DisplayName("the token is read back out of the request")
    void readsTheCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("other", "x"), new Cookie(RefreshTokenCookie.NAME, "the-token"));

        assertThat(RefreshTokenCookie.readOrThrow(request)).isEqualTo("the-token");
    }

    @Test
    @DisplayName("a request without the cookie fails distinguishably, not with an NPE")
    void missingCookieThrows() {
        assertThatThrownBy(() -> RefreshTokenCookie.readOrThrow(new MockHttpServletRequest()))
            .isInstanceOf(MissingRefreshTokenException.class);
    }

    @Test
    @DisplayName("a request with other cookies but not this one still fails distinguishably")
    void otherCookiesOnlyThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("session", "x"));

        assertThatThrownBy(() -> RefreshTokenCookie.readOrThrow(request))
            .isInstanceOf(MissingRefreshTokenException.class);
    }
}
