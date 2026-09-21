package com.aura.catalog.media;

import com.aura.common.web.error.BusinessRuleException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The cross-service check behind every media attachment.
 *
 * <p>Two behaviours here are load-bearing and neither is obvious from reading the call site. The
 * seller's own token is forwarded, which is what makes media-service's owner-scoped endpoint answer
 * "is this file theirs" as well as "is it servable". And an unreachable media-service fails closed:
 * treating it as "probably fine" is how a product goes live with unscanned images on it.
 */
class HttpMediaGatewayTest {

    private static final String TOKEN = "Bearer seller-token";
    private static final String BASE_URL = "http://media-service:8084";

    private MockRestServiceServer server;
    private HttpMediaGateway gateway;
    private UUID mediaId;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        gateway = new HttpMediaGateway(builder, new MediaGatewayProperties(BASE_URL));
        mediaId = UUID.randomUUID();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, TOKEN);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void clearContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void respondWith(String json) {
        server.expect(requestTo(BASE_URL + "/api/media/" + mediaId))
            .andExpect(header(HttpHeaders.AUTHORIZATION, TOKEN))
            .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("a READY file the seller owns is usable")
    void readyFileIsUsable() {
        respondWith("""
            {"id":"%s","status":"READY"}
            """.formatted(mediaId));

        assertThat(gateway.isUsableBy(mediaId)).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("a file still being scanned is not usable")
    void scanningFileIsNotUsable() {
        respondWith("""
            {"id":"%s","status":"SCANNING"}
            """.formatted(mediaId));

        assertThat(gateway.isUsableBy(mediaId)).isFalse();
    }

    @Test
    @DisplayName("a quarantined file is not usable")
    void quarantinedFileIsNotUsable() {
        respondWith("""
            {"id":"%s","status":"QUARANTINED"}
            """.formatted(mediaId));

        assertThat(gateway.isUsableBy(mediaId)).isFalse();
    }

    @Test
    @DisplayName("a 404 means either unknown or not yours, and both are simply not usable")
    void notFoundIsNotUsable() {
        // media-service deliberately does not distinguish the two, so neither does this: telling a
        // seller "that file exists but is not yours" confirms someone else's id.
        server.expect(requestTo(BASE_URL + "/api/media/" + mediaId))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(gateway.isUsableBy(mediaId)).isFalse();
    }

    @Test
    @DisplayName("an unreachable media-service fails closed, not open")
    void serverErrorFailsClosed() {
        server.expect(requestTo(BASE_URL + "/api/media/" + mediaId))
            .andRespond(withServerError());

        assertThatThrownBy(() -> gateway.isUsableBy(mediaId))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("Could not verify");
    }

    @Test
    @DisplayName("the seller's own token is forwarded, which is what proves ownership")
    void forwardsTheCallersToken() {
        // Using a service account instead would make every file look equally accessible, and
        // catalog has no record of who uploaded what to check it itself.
        server.expect(requestTo(BASE_URL + "/api/media/" + mediaId))
            .andExpect(header(HttpHeaders.AUTHORIZATION, TOKEN))
            .andRespond(withSuccess("""
                {"id":"%s","status":"READY"}
                """.formatted(mediaId), MediaType.APPLICATION_JSON));

        gateway.isUsableBy(mediaId);
        server.verify();
    }

    @Test
    @DisplayName("called outside a request it fails loudly rather than silently unauthenticated")
    void outsideARequestItThrows() {
        RequestContextHolder.resetRequestAttributes();

        assertThatThrownBy(() -> gateway.isUsableBy(mediaId))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No bearer token");
    }

    @Test
    @DisplayName("a request with no Authorization header fails the same way")
    void missingAuthorizationHeaderThrows() {
        RequestContextHolder.setRequestAttributes(
            new ServletRequestAttributes(new MockHttpServletRequest()));

        assertThatThrownBy(() -> gateway.isUsableBy(mediaId))
            .isInstanceOf(IllegalStateException.class);
    }
}
