package com.aura.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param baseUrl        where catalog is reachable inside the network. Direct rather than through
 *                       the gateway: the gateway authenticates traffic arriving from outside, and
 *                       an internal call does not need a second hop that can be down.
 * @param internalApiKey shared secret for catalog's {@code /api/internal/**}. No default — an
 *                       unset key makes every call fail closed rather than silently unauthenticated.
 */
@ConfigurationProperties(prefix = "aura.order.catalog")
public record CatalogClientProperties(
    @DefaultValue("http://catalog-service:8083") String baseUrl,
    String internalApiKey
) {
}
