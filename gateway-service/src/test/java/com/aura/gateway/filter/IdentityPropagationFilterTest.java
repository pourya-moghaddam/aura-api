package com.aura.gateway.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The identity headers downstream services trust.
 *
 * <p>The filter's own javadoc names the stakes: catalog-service and order-service read {@code
 * X-User-Id} and {@code X-User-Roles} without re-deriving them, so a request that arrives carrying
 * its own copies and keeps them is an authentication bypass — anyone could claim to be user 1 with
 * SUPER_ADMIN. Every test here is ultimately about that one sentence.
 */
class IdentityPropagationFilterTest {

    private final IdentityPropagationFilter filter = new IdentityPropagationFilter();

    /** Captures the exchange as mutated, which is the only place the effect is observable. */
    private final AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
    private final GatewayFilterChain chain = exchange -> {
        forwarded.set(exchange);
        return Mono.empty();
    };

    @Nested
    @DisplayName("a client-supplied identity header")
    class Spoofing {

        @Test
        @DisplayName("is stripped from an anonymous request")
        void strippedWhenAnonymous() {
            MockServerWebExchange exchange = exchangeClaiming("1", "SUPER_ADMIN");

            StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

            assertThat(headersOfForwarded().get(IdentityPropagationFilter.USER_ID_HEADER)).isNull();
            assertThat(headersOfForwarded().get(IdentityPropagationFilter.USER_ROLES_HEADER)).isNull();
        }

        @Test
        @DisplayName("is replaced by the token's, never appended to")
        void replacedWhenAuthenticated() {
            MockServerWebExchange exchange = exchangeClaiming("1", "SUPER_ADMIN");

            StepVerifier.create(withAuthenticated(exchange, "42", List.of("CUSTOMER")))
                .verifyComplete();

            // Single-valued, not ["1", "42"]. `add` after `remove` is what makes this true, and an
            // append would hand downstream two identities to choose between.
            assertThat(headersOfForwarded().get(IdentityPropagationFilter.USER_ID_HEADER))
                .containsExactly("42");
            assertThat(headersOfForwarded().get(IdentityPropagationFilter.USER_ROLES_HEADER))
                .containsExactly("CUSTOMER");
        }

        @Test
        @DisplayName("is stripped when the authentication is not a JWT")
        void strippedWhenNotAJwt() {
            MockServerWebExchange exchange = exchangeClaiming("1", "SUPER_ADMIN");
            var authentication = new TestingAuthenticationToken("someone", "credentials", "ROLE_USER");

            StepVerifier.create(filter.filter(exchange, chain)
                    .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication)))
                .verifyComplete();

            assertThat(headersOfForwarded().get(IdentityPropagationFilter.USER_ID_HEADER)).isNull();
        }
    }

    @Test
    @DisplayName("several roles travel as one comma-separated header")
    void rolesAreJoined() {
        StepVerifier.create(withAuthenticated(exchangeClaiming(null, null), "7",
                List.of("ADMIN", "SELLER"))).verifyComplete();

        assertThat(headersOfForwarded().getFirst(IdentityPropagationFilter.USER_ROLES_HEADER))
            .isEqualTo("ADMIN,SELLER");
    }

    @Test
    @DisplayName("a token with no roles claim sends an empty header, not the word null")
    void missingRolesClaimIsEmpty() {
        // `String.join` on a null list throws, and the header would otherwise read "null" — which
        // downstream would parse as a role by that name rather than as an absence.
        StepVerifier.create(withAuthenticated(exchangeClaiming(null, null), "7", null))
            .verifyComplete();

        assertThat(headersOfForwarded().getFirst(IdentityPropagationFilter.USER_ROLES_HEADER))
            .isEmpty();
    }

    @Test
    @DisplayName("runs before the routing filter")
    void ordering() {
        // A filter ordered after routing would mutate a request that has already been sent, so the
        // stripping would silently do nothing.
        assertThat(filter.getOrder()).isLessThan(Ordered.LOWEST_PRECEDENCE);
    }

    private Mono<Void> withAuthenticated(MockServerWebExchange exchange, String subject, List<String> roles) {
        Jwt.Builder jwt = Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .subject(subject)
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300));
        if (roles != null) jwt.claim("roles", roles);

        var authentication = new JwtAuthenticationToken(jwt.build(),
            List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));

        return filter.filter(exchange, chain)
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
    }

    /** A request that arrives already asserting who it is — the attack this filter exists for. */
    private MockServerWebExchange exchangeClaiming(String userId, String roles) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get("/api/orders/mine");
        if (userId != null) {
            request.header(IdentityPropagationFilter.USER_ID_HEADER, userId)
                .header(IdentityPropagationFilter.USER_ROLES_HEADER, roles);
        }
        return MockServerWebExchange.from(request.build());
    }

    private HttpHeaders headersOfForwarded() {
        assertThat(forwarded.get()).describedAs("the chain was never called").isNotNull();
        return forwarded.get().getRequest().getHeaders();
    }
}
