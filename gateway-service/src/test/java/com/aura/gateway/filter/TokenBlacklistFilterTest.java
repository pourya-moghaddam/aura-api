package com.aura.gateway.filter;

import com.aura.common.security.TokenBlacklistKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Revocation at the edge.
 *
 * <p>A signed access token is valid until it expires, which is what makes signing out hard: the
 * token in the shopper's browser stays cryptographically perfect after they press the button. The
 * blacklist is what closes that window, so "a revoked token is refused" is the single claim worth
 * testing — and the two ways to get it wrong are refusing nothing and refusing everything.
 */
@ExtendWith(MockitoExtension.class)
class TokenBlacklistFilterTest {

    @Mock
    private ReactiveStringRedisTemplate redisTemplate;

    @InjectMocks
    private TokenBlacklistFilter filter;

    /** Counts rather than captures: running the chain twice is a real failure mode here. */
    private final AtomicInteger chainCalls = new AtomicInteger();
    private final GatewayFilterChain chain = exchange -> {
        chainCalls.incrementAndGet();
        return Mono.empty();
    };

    private final MockServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.get("/api/orders/mine").build());

    @Test
    @DisplayName("a revoked token is refused with 401 and never reaches the service")
    void revokedTokenIsRejected() {
        given(redisTemplate.hasKey(TokenBlacklistKeys.forTokenId("revoked-jti")))
            .willReturn(Mono.just(true));

        StepVerifier.create(authenticatedWith("revoked-jti")).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // The status alone is not enough: a filter that sets 401 and still forwards would leave
        // the downstream write already done.
        assertThat(chainCalls).hasValue(0);
    }

    @Test
    @DisplayName("a live token passes through")
    void liveTokenIsForwarded() {
        given(redisTemplate.hasKey(TokenBlacklistKeys.forTokenId("good-jti")))
            .willReturn(Mono.just(false));

        StepVerifier.create(authenticatedWith("good-jti")).verifyComplete();

        assertThat(chainCalls).hasValue(1);
        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("an anonymous request is forwarded without touching Redis")
    void anonymousSkipsTheLookup() {
        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chainCalls).hasValue(1);
        // Storefront browsing is anonymous and is most of the traffic. A Redis round-trip per
        // request would be a cost paid on every page view to answer a question about a token that
        // does not exist.
        verify(redisTemplate, never()).hasKey(any());
    }

    @Test
    @DisplayName("the chain runs exactly once for a live token")
    void chainIsNotRunTwice() {
        given(redisTemplate.hasKey(any())).willReturn(Mono.just(false));

        StepVerifier.create(authenticatedWith("good-jti")).verifyComplete();

        // The trap the filter's own comment describes: chain.filter() completes empty, so a
        // switchIfEmpty placed after it would run the whole remaining chain a second time — and
        // for a checkout that is a second order.
        assertThat(chainCalls).hasValue(1);
    }

    @Test
    @DisplayName("a token with no jti is forwarded rather than refused")
    void missingTokenIdIsForwarded() {
        // `mapNotNull` drops it. Refusing instead would lock out every holder of a token minted
        // before jti was issued, which is a self-inflicted outage rather than a security gain.
        StepVerifier.create(authenticatedWith(null)).verifyComplete();

        assertThat(chainCalls).hasValue(1);
    }

    @Test
    @DisplayName("runs early, before anything with a side effect")
    void ordering() {
        assertThat(filter.getOrder()).isLessThan(Ordered.LOWEST_PRECEDENCE);
    }

    private Mono<Void> authenticatedWith(String tokenId) {
        Jwt.Builder jwt = Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .subject("42")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300));
        if (tokenId != null) jwt.jti(tokenId);

        var authentication = new JwtAuthenticationToken(jwt.build(),
            List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));

        return filter.filter(exchange, chain)
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
    }
}
