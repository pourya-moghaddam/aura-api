package com.aura.search.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.aura.search.config.SearchProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports whether the alias this service reads through actually exists.
 *
 * <p>A cluster that is up and an index that is missing look identical to a plain connection check,
 * and the difference is the difference between working search and every query returning nothing.
 * The bootstrapper deliberately does not crash the service when it cannot create the index, so
 * this is what makes that gap visible.
 */
@Component
@RequiredArgsConstructor
public class IndexHealthIndicator implements HealthIndicator {

    private final ElasticsearchClient client;
    private final SearchProperties properties;

    @Override
    public Health health() {
        try {
            boolean exists = client.indices().existsAlias(a -> a.name(properties.alias())).value();

            return exists
                ? Health.up().withDetail("alias", properties.alias()).build()
                : Health.down()
                    .withDetail("alias", properties.alias())
                    .withDetail("reason", "the alias does not exist; nothing can be searched")
                    .build();

        } catch (Exception e) {
            return Health.down(e).withDetail("alias", properties.alias()).build();
        }
    }
}
