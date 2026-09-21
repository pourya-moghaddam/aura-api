package com.aura.search.trending;

import com.aura.common.events.OrderPaidEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class OrderPaidConsumerTest {

    private final SalesCounter salesCounter = mock(SalesCounter.class);
    private final Consumer<OrderPaidEvent> consumer =
        new OrderPaidConsumer(salesCounter).orderPaid();

    @Test
    @DisplayName("counts the sale")
    void countsTheSale() {
        OrderPaidEvent event = event(List.of(new OrderPaidEvent.Line(1L, 10L, 2)));

        consumer.accept(event);

        verify(salesCounter).record(event);
    }

    @Test
    @DisplayName("an order with no lines is dropped rather than retried")
    void emptyOrderIsDropped() {
        // It cannot become valid on a redelivery, so retrying it three times and then dead-lettering
        // it would only add noise to the DLQ that someone has to read.
        consumer.accept(event(List.of()));
        consumer.accept(event(null));

        verify(salesCounter, never()).record(org.mockito.ArgumentMatchers.any());
    }

    private OrderPaidEvent event(List<OrderPaidEvent.Line> lines) {
        return new OrderPaidEvent(UUID.randomUUID(), Instant.now(), 1L, "TRACE", lines);
    }
}
