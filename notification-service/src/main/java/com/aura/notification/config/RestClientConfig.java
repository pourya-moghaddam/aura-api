package com.aura.notification.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    /**
     * Built from the auto-configured builder, so the {@code spring.http.client} timeouts apply.
     *
     * <p>Those timeouts are not decoration. Without them a provider that accepts the connection and
     * then stops talking holds a consumer thread indefinitely and the queue stops moving behind it.
     * A hung SMS gateway should cost one message a retry, not the whole notification stream.
     */
    @Bean
    public RestClient restClient(RestClient.Builder builder) {
        return builder.build();
    }
}
