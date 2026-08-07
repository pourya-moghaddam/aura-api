package com.aura.gateway.filter;

import com.aura.common.security.TokenBlacklistKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Rejects tokens that have been explicitly revoked since they were issued.
 *
 * <p>Checked here rather than in each service so a single Redis round-trip covers every route.
 * The set is keyed by {@code jti} and self-expiring, so it never holds more than one access-token
 * lifetime of entries.
 */
@Component
@RequiredArgsConstructor
public class TokenBlacklistFilter implements GlobalFilter, Ordered {

    private final ReactiveStringRedisTemplate redisTemplate;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
            .map(SecurityContext::getAuthentication)
            .filter(JwtAuthenticationToken.class::isInstance)
            .cast(JwtAuthenticationToken.class)
            .mapNotNull(token -> token.getToken().getId())
            .flatMap(tokenId -> redisTemplate.hasKey(TokenBlacklistKeys.forTokenId(tokenId)))
            // Exactly one element reaches the flatMap below. Collapsing the "anonymous request"
            // and "not blacklisted" cases into a single `false` avoids the switchIfEmpty trap:
            // chain.filter() itself completes empty, so an empty-fallback placed after it would
            // run the rest of the chain a second time.
            .defaultIfEmpty(Boolean.FALSE)
            .flatMap(blacklisted -> Boolean.TRUE.equals(blacklisted)
                ? reject(exchange)
                : chain.filter(exchange));
    }

    private Mono<Void> reject(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }
}
