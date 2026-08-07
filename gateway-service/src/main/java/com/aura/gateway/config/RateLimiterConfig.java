package com.aura.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

/**
 * Keys for the Redis-backed {@code RequestRateLimiter} filter.
 */
@Configuration
public class RateLimiterConfig {

    private static final String FORWARDED_FOR = "X-Forwarded-For";

    /**
     * Rate limit by client IP.
     *
     * <p>Reads the first hop of {@code X-Forwarded-For}, which is only trustworthy because Nginx
     * sits in front and <em>overwrites</em> rather than appends to that header. If the gateway is
     * ever exposed directly, this becomes trivially spoofable — a client sets the header and gets
     * a fresh bucket per request. That deployment assumption is load-bearing.
     */
    /*
     * @Primary because RequestRateLimiterGatewayFilterFactory injects a single KeyResolver as its
     * default, and two candidate beans make that ambiguous - the gateway then fails to start with
     * "expected single matching bean but found 2". The per-route `#{@userKeyResolver}` references
     * in application.yml still select by name; this only decides the fallback.
     */
    @Bean
    @Primary
    public KeyResolver ipKeyResolver() {
        return exchange -> Mono.just(clientIp(exchange));
    }

    /**
     * Rate limit per authenticated user, falling back to IP for anonymous callers.
     */
    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> ReactiveSecurityContextHolder.getContext()
            .map(SecurityContext::getAuthentication)
            .filter(authentication -> authentication != null && authentication.isAuthenticated())
            .map(authentication -> "user:" + authentication.getName())
            .defaultIfEmpty("ip:" + clientIp(exchange));
    }

    private String clientIp(ServerWebExchange exchange) {
        String forwarded = exchange.getRequest().getHeaders().getFirst(FORWARDED_FOR);
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
        return remoteAddress != null ? remoteAddress.getAddress().getHostAddress() : "unknown";
    }
}
