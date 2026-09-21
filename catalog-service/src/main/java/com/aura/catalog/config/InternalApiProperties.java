package com.aura.catalog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param apiKey shared secret for service-to-service calls on {@code /api/internal/**}. Must be set
 *               in any environment where those endpoints are reachable; startup fails otherwise
 *               rather than defaulting to something guessable — see {@code InternalApiKeyFilter}.
 */
@ConfigurationProperties(prefix = "aura.catalog.internal")
public record InternalApiProperties(String apiKey) {

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
