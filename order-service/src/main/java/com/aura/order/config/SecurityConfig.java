package com.aura.order.config;

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
 * Most of this service is reachable without an account, which is unusual and deliberate.
 *
 * <p>Requirement 12 makes guests first-class buyers: a shopper must be able to fill a cart, check
 * out and pay without ever signing in. So the cart and checkout paths are anonymous, and identity
 * — when there is any — comes from the optional bearer token rather than being demanded up front.
 * What protects a guest's cart is the unguessable token in their httpOnly cookie, not
 * authentication.
 *
 * <p>Everything under {@code /api/control} still requires a control-audience token, and the
 * per-role rules live on the handler methods.
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
            // CORS is terminated at the gateway; this service is not reachable from a browser.
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
                // Guest checkout: cart, delivery options and the payment callback all have to work
                // for someone with no account and no token.
                .requestMatchers("/api/orders/cart/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/orders/delivery-methods").permitAll()
                .requestMatchers("/api/orders/checkout", "/api/orders/checkout/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/orders/discounts/quote").permitAll()
                // The gateway returns the shopper here with no credential of ours at all,
                // which is exactly why nothing it says is believed without a server-side
                // verify.
                .requestMatchers("/api/orders/payments/callback",
                    "/api/orders/payments/callback/**").permitAll()
                .requestMatchers("/api/orders/payments/start/**").permitAll()
                // A seller-composed order link. The token in the URL is the whole
                // credential, because the buyer has no account to sign into.
                .requestMatchers("/api/orders/links/**").permitAll()
                // Looking an order up by its trace code is how a guest checks on it afterwards -
                // the code is the credential, which is why it is 10 random characters and not the
                // primary key.
                .requestMatchers(HttpMethod.GET, "/api/orders/track/**").permitAll()
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
            );

        return http.build();
    }
}
