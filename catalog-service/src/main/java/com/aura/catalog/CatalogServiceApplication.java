package com.aura.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduling is on because the stock-reservation expiry sweep depends on it, and its absence is
 * invisible: {@code @Scheduled} without {@code @EnableScheduling} is not an error, the method is
 * simply never called. Abandoned checkouts would hold stock forever with nothing in the logs to
 * say so.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class CatalogServiceApplication {

    static void main(String[] args) {
        SpringApplication.run(CatalogServiceApplication.class, args);
    }
}
