package com.aura.media.config;

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

/**
 * Everything here requires authentication, with exactly one exception.
 *
 * <p>That exception is {@code GET /api/media/{id}/content}, and it is deliberately narrow. A
 * shopper browsing the catalogue is anonymous by design, so product photos have to be fetchable
 * without a token; every other route in this service stays owner-scoped, including the two that
 * publish and unpublish.
 *
 * <p>Permitting the path does not widen what is reachable through it. The endpoint can only resolve
 * a file that is both {@code PUBLIC} and {@code READY}, and both conditions are in the query rather
 * than in a check afterwards — see {@code MediaFileRepository#findByIdAndVisibilityAndStatus}. An
 * unpublished or unscanned file 404s here exactly as an absent one does.
 *
 * <p>Raw bucket paths still never leave this service: the public endpoint streams the bytes rather
 * than redirecting to storage, so the object key stays as hidden as it was before.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final AuraJwtAuthenticationConverter jwtAuthenticationConverter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            // CORS is terminated at the gateway; this service is not browser-reachable directly.
            .cors(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Everything under /actuator, and only because it no longer answers on this
                // port at all: management.server.port moves it to 9091, which is never published.
                // Permitting it here is what lets Prometheus scrape over the Docker network.
                .requestMatchers("/actuator/**").permitAll()
                // The API description and the page that renders it. Public in development so a
                // frontend developer can read it without a token; compose.prod.yaml switches
                // springdoc off entirely rather than relying on this being locked down.
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                // Storefront product imagery. GET and HEAD only, named explicitly so that adding a
                // POST or DELETE under the same path later does not silently inherit this.
                //
                // HEAD matters and is easy to miss: Spring MVC answers it from the same handler,
                // but Spring Security matches on the literal method, so permitting GET alone makes
                // every cache revalidation and every `curl -I` come back 401 while the page itself
                // works. That asymmetry is genuinely confusing to debug.
                .requestMatchers(HttpMethod.GET, "/api/media/*/content").permitAll()
                .requestMatchers(HttpMethod.HEAD, "/api/media/*/content").permitAll()
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
            );

        return http.build();
    }
}
