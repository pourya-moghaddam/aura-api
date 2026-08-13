package com.aura.search;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The read model.
 *
 * <p>Owns an Elasticsearch index fed from {@code ProductChanged} and serves storefront search,
 * category browse and facets. It holds no source of truth — everything here can be rebuilt from
 * catalog, which is what makes a reindex an ordinary operation rather than a crisis.
 *
 * <p>{@code @EnableScheduling} because the trending refresh and the index health check are
 * {@code @Scheduled}; without it they are silently never called and nothing complains.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class SearchServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SearchServiceApplication.class, args);
    }
}
