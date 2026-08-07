package com.aura.common.security;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;

/**
 * Supplies a JWKS-backed {@link JwtDecoder} and the Aura authority converter to every servlet
 * service.
 *
 * <p>Servlet-only. The gateway is WebFlux and builds a {@code NimbusReactiveJwtDecoder} from the
 * same {@link AuraSecurityProperties}; the validators are the shared part, the decoder type is not.
 *
 * <p>Each service still declares its own {@code SecurityFilterChain}, because which paths are
 * public differs per service and that is not something to guess centrally.
 */
@AutoConfiguration
@ConditionalOnClass(JwtDecoder.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(AuraSecurityProperties.class)
public class AuraSecurityAutoConfiguration {

    /**
     * Conditional on the property because auth-service does not set it: it verifies with the public
     * key it already holds rather than fetching its own JWKS over HTTP, and supplies its own
     * {@code JwtDecoder} bean.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "aura.security.jwt", name = "jwk-set-uri")
    public JwtDecoder jwtDecoder(AuraSecurityProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();
        decoder.setJwtValidator(defaultValidator(properties));
        return decoder;
    }

    @Bean
    @ConditionalOnMissingBean
    public AuraJwtAuthenticationConverter auraJwtAuthenticationConverter() {
        return new AuraJwtAuthenticationConverter();
    }

    /**
     * Expiry and not-before from {@code createDefault}, plus a strict issuer check so a token
     * signed by some other JWKS-publishing service cannot be replayed here.
     */
    public static OAuth2TokenValidator<Jwt> defaultValidator(AuraSecurityProperties properties) {
        return new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefault(),
            new JwtIssuerValidator(properties.issuer())
        );
    }
}
