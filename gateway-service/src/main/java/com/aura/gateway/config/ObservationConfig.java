package com.aura.gateway.config;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;

/**
 * The same suppression common-web applies to the servlet services, for the one that is reactive.
 *
 * <p>common-web's predicate is conditional on a servlet web application and inspects the servlet
 * observation context, so it does nothing here. Without this the gateway is the only service still
 * turning every Prometheus scrape and Docker health probe into a trace - and being the front door,
 * it is the service whose traces someone actually goes looking through.
 */
@Configuration
public class ObservationConfig {

    @Bean
    public ObservationPredicate actuatorObservationPredicate() {
        return (name, context) -> {
            if (!(context instanceof ServerRequestObservationContext serverContext)) {
                return true;
            }
            String path = serverContext.getCarrier().getPath().value();
            return !path.startsWith("/actuator");
        };
    }
}
