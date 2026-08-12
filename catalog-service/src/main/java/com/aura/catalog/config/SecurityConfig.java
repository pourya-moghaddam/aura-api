package com.aura.catalog.config;

import com.aura.common.security.AuraJwtAuthenticationConverter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Catalog reads are public — that is the storefront. Everything else requires a token, and the
 * per-role and per-seller rules live on the handler methods via {@code @PreAuthorize}.
 *
 * <p>Deny-by-default: any path not listed as public needs authentication. Phase 3 adds the
 * fine-grained rules as the real endpoints land.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final AuraJwtAuthenticationConverter jwtAuthenticationConverter;
    private final InternalApiProperties internalApiProperties;

    /**
     * Anonymous storefront reads, in their own chain so they can be cached.
     *
     * <p>Spring Security writes {@code Cache-Control: no-cache, no-store, must-revalidate} on every
     * response by default, and it wins over whatever a controller sets — so the one-minute TTL on
     * the homepage slider was being discarded silently. The header looked right in the code and
     * never reached a browser.
     *
     * <p>Disabling that writer only here, rather than globally, is the point: these responses are
     * public catalogue data served to anonymous callers, and there is nothing user-specific in
     * them. Turning it off for the whole service would let a proxy cache an authenticated seller's
     * draft listings.
     */
    @Bean
    @org.springframework.core.annotation.Order(1)
    public SecurityFilterChain publicCatalogFilterChain(HttpSecurity http) throws Exception {
        http
            // HEAD as well as GET: caches and proxies use HEAD to revalidate, and matching only
            // GET sent those to the authenticated chain, where a public storefront path 401s.
            .securityMatcher(request ->
                (HttpMethod.GET.matches(request.getMethod())
                    || HttpMethod.HEAD.matches(request.getMethod()))
                    && request.getRequestURI().startsWith("/api/catalog/"))
            .csrf(AbstractHttpConfigurer::disable)
            .cors(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .headers(headers -> headers.cacheControl(cache -> cache.disable()));

        return http.build();
    }

    @Bean
    @org.springframework.core.annotation.Order(2)
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            // CORS is terminated at the gateway; this service is not reachable from a browser.
            .cors(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/catalog/**").permitAll()
                .requestMatchers(HttpMethod.HEAD, "/api/catalog/**").permitAll()
                // Not open: authenticated by the API-key filter below, which runs first and
                // rejects anything without the shared key. These carry no user identity at all,
                // because guest checkout means there may not be one.
                .requestMatchers("/api/internal/**").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(new InternalApiKeyFilter(internalApiProperties),
                UsernamePasswordAuthenticationFilter.class)
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
            );

        return http.build();
    }
}
