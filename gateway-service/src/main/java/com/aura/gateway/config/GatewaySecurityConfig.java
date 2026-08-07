package com.aura.gateway.config;

import com.aura.common.security.AuraJwtAuthenticationConverter;
import com.aura.common.security.AuraSecurityProperties;
import com.aura.common.security.TokenAudience;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * The edge security policy.
 *
 * <p>Note what is <em>not</em> here: most routes are {@code permitAll}. The gateway is not trying to
 * be the only authorization layer — storefront browsing is genuinely public, and every downstream
 * service enforces its own rules regardless. What the gateway owns is the checks that must not be
 * skippable: token signature validity, and the control-panel audience boundary.
 */
@Configuration
@EnableWebFluxSecurity
@EnableConfigurationProperties(AuraSecurityProperties.class)
public class GatewaySecurityConfig {

    @Bean
    public ReactiveJwtDecoder reactiveJwtDecoder(AuraSecurityProperties properties) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder
            .withJwkSetUri(properties.jwkSetUri())
            .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefault(),
            new JwtIssuerValidator(properties.issuer())
        ));
        return decoder;
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, ReactiveJwtDecoder jwtDecoder) {
        var converter = new ReactiveJwtAuthenticationConverterAdapter(new AuraJwtAuthenticationConverter());

        return http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
            .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .authorizeExchange(exchange -> exchange
                .pathMatchers("/actuator/health/**", "/actuator/info").permitAll()
                // The control-panel boundary. A storefront-audience token is rejected here even if
                // the user holds ADMIN, so a plain session cannot be walked into the panel.
                .pathMatchers("/api/control/**").hasAuthority(TokenAudience.CONTROL.authority())
                // Everything else is open at the edge and authorized by the owning service.
                // A token that is present but invalid still fails, via the resource server below.
                .anyExchange().permitAll()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtDecoder(jwtDecoder).jwtAuthenticationConverter(converter))
            )
            .build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // Explicit origins, not wildcards: credentials are allowed, and the two combined is both
        // rejected by browsers and a genuine hole.
        config.setAllowedOriginPatterns(List.of(
            "https://*.aura.local",
            "https://aura.local",
            "http://localhost:[*]"
        ));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("X-Correlation-Id"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
