package com.aura.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Orders, carts, discounts and payment.
 *
 * <p>Scheduling is on from the start and several things depend on it: the outbox drain, the sweep
 * of abandoned guest carts, and — the one that costs real money if it never runs — the
 * reconciliation of payments left pending because a gateway callback went missing.
 * {@code @Scheduled} without {@code @EnableScheduling} is not an error; the method is simply never
 * called.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class OrderServiceApplication {

    static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
