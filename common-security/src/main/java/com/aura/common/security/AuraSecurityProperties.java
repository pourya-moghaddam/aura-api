package com.aura.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param jwkSetUri where auth-service publishes its public keys
 * @param issuer    expected {@code iss} claim; a token from anywhere else is rejected
 */
@ConfigurationProperties("aura.security.jwt")
public record AuraSecurityProperties(String jwkSetUri, String issuer) {
}
