package com.aura.gateway.filter;

import com.aura.common.security.AuraClaims;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Replaces the {@code X-User-*} headers on every proxied request with values derived from the
 * validated token.
 *
 * <p>The stripping is the important half. Downstream services read these headers for convenience,
 * so if a client could set them itself and have them pass through, anyone could claim to be user 1
 * with the SUPER_ADMIN role. Headers are therefore removed unconditionally — including on
 * anonymous requests — and only ever re-added from a token this gateway has verified.
 */
@Component
public class IdentityPropagationFilter implements GlobalFilter, Ordered {

    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String USER_ROLES_HEADER = "X-User-Roles";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
            .map(context -> context.getAuthentication())
            .filter(Authentication::isAuthenticated)
            .filter(JwtAuthenticationToken.class::isInstance)
            .cast(JwtAuthenticationToken.class)
            .map(token -> withIdentity(exchange, token))
            // No authentication in context: strip and forward as anonymous.
            .defaultIfEmpty(withoutIdentity(exchange))
            .flatMap(chain::filter);
    }

    private ServerWebExchange withIdentity(ServerWebExchange exchange, JwtAuthenticationToken token) {
        Jwt jwt = token.getToken();
        List<String> roles = jwt.getClaimAsStringList(AuraClaims.ROLES);

        ServerHttpRequest request = exchange.getRequest().mutate()
            .headers(headers -> {
                headers.remove(USER_ID_HEADER);
                headers.remove(USER_ROLES_HEADER);
                headers.add(USER_ID_HEADER, jwt.getSubject());
                headers.add(USER_ROLES_HEADER, roles == null ? "" : String.join(",", roles));
            })
            .build();

        return exchange.mutate().request(request).build();
    }

    private ServerWebExchange withoutIdentity(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest().mutate()
            .headers(headers -> {
                headers.remove(USER_ID_HEADER);
                headers.remove(USER_ROLES_HEADER);
            })
            .build();

        return exchange.mutate().request(request).build();
    }

    @Override
    public int getOrder() {
        // Before the routing filter, so the mutated request is what actually gets proxied.
        return Ordered.LOWEST_PRECEDENCE - 1;
    }
}
