package com.aura.auth.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-IP OTP limits are only as good as this. If it returned the proxy's address for every
 * request, one attacker would share a bucket with every legitimate user; if it trusted an
 * appended header, an attacker would get a fresh bucket per request.
 */
class ClientIpTest {

    @Test
    @DisplayName("the first hop of X-Forwarded-For is the client")
    void firstHopIsTheClient() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.9, 10.0.0.1, 10.0.0.2");

        assertThat(ClientIp.of(request)).isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("whitespace around the address is trimmed")
    void trimsWhitespace() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "  203.0.113.9  , 10.0.0.1");

        assertThat(ClientIp.of(request)).isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("a single-value header works")
    void singleValue() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.9");

        assertThat(ClientIp.of(request)).isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("without the header it falls back to the socket address")
    void fallsBackToRemoteAddr() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.4");

        assertThat(ClientIp.of(request)).isEqualTo("198.51.100.4");
    }

    @Test
    @DisplayName("a blank header falls back rather than returning an empty bucket key")
    void blankHeaderFallsBack() {
        // An empty string would be a single shared rate-limit bucket for everyone who sent one.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "   ");
        request.setRemoteAddr("198.51.100.4");

        assertThat(ClientIp.of(request)).isEqualTo("198.51.100.4");
    }
}
