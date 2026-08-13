package com.aura.notification.config;

import com.aura.common.events.OrderItemStatusChangedEvent;
import com.aura.notification.order.OrderStatusNotifier;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Consumer;

@Configuration
@RequiredArgsConstructor
public class OrderStatusConsumerConfig {

    private final OrderStatusNotifier orderStatusNotifier;

    /**
     * Exceptions escape on purpose, so a transient provider failure is retried and then
     * dead-lettered rather than dropped.
     */
    @Bean
    public Consumer<OrderItemStatusChangedEvent> orderItemStatusChanged() {
        return orderStatusNotifier::statusChanged;
    }
}
