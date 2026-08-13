package com.aura.search.trending;

import com.aura.common.events.OrderPaidEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Consumer;

/**
 * What sold, from order-service.
 *
 * <p>Tied to payment rather than to checkout: an order abandoned at the gateway says nothing about
 * demand, and counting it would let anyone inflate a product's popularity by filling a basket.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class OrderPaidConsumer {

    private final SalesCounter salesCounter;

    @Bean
    public Consumer<OrderPaidEvent> orderPaid() {
        return event -> {
            if (event.lines() == null || event.lines().isEmpty()) {
                log.warn("Ignoring an OrderPaid with no lines: {}", event.eventId());
                return;
            }
            salesCounter.record(event);
        };
    }
}
