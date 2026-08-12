package com.aura.catalog.media;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param baseUrl where media-service is reachable from inside the network. A direct address rather
 *                than going back out through the gateway: the gateway's job is to authenticate
 *                traffic arriving from outside, and routing an internal call through it would add
 *                a hop and a second point of failure to answer a question a service can ask
 *                directly.
 */
@ConfigurationProperties(prefix = "aura.catalog.media")
public record MediaGatewayProperties(
    @DefaultValue("http://media-service:8084") String baseUrl
) {
}
