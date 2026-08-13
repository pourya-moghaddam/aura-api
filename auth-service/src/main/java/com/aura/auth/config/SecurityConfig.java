package com.aura.auth.config;

import com.aura.common.security.AuraJwtAuthenticationConverter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Auth-service is both an issuer and a resource server: it mints tokens on the public login
 * endpoints, and validates them on the authenticated ones like {@code /me}.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final AuraJwtAuthenticationConverter jwtAuthenticationConverter;

    /**
     * Delegating encoder: hashes new passwords with the current default and still verifies older
     * {@code {bcrypt}}-prefixed hashes. That is what makes changing the algorithm later a config
     * change rather than a forced password reset for every user.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // No cookies are used for API authentication, so there is no CSRF surface here.
            // The refresh-token cookie added in Phase 1 is SameSite=Lax and only accepted on a
            // dedicated endpoint, which is handled there.
            .csrf(AbstractHttpConfigurer::disable)
            .cors(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Pre-authentication endpoints. Each is rate limited in OtpRateLimiter rather
                // than here, because the limits are per phone and per IP, not per route.
                .requestMatchers(
                    "/api/auth/storefront/otp/request",
                    "/api/auth/storefront/otp/verify",
                    "/api/auth/storefront/login",
                    "/api/auth/control/otp/request",
                    "/api/auth/control/otp/verify",
                    "/api/auth/control/login",
                    "/api/auth/login-methods",
                    // The access token has necessarily expired by the time a client needs this -
                    // the refresh cookie is the only credential available, and it is validated
                    // inside the handler, not by this filter chain.
                    "/api/auth/token/refresh"
                ).permitAll()
                .requestMatchers("/api/auth/.well-known/**").permitAll()
                // Everything under /actuator, and only because it no longer answers on this
                // port at all: management.server.port moves it to 9091, which is never published.
                // Permitting it here is what lets Prometheus scrape over the Docker network.
                .requestMatchers("/actuator/**").permitAll()
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
            );

        return http.build();
    }
}
