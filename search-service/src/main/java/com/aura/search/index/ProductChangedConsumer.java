package com.aura.search.index;

import com.aura.common.events.ProductChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Consumer;

/**
 * The catalog → search feed.
 *
 * <p>Thin on purpose: everything interesting is in {@link ProductIndexer}, which can be tested
 * against a real Elasticsearch without a broker in the way.
 *
 * <p>Failures are allowed to propagate. The binder retries with backoff and then routes to the
 * dead-letter topic, so a poison message stops itself rather than stopping every product behind
 * it — and the index can always be rebuilt from catalog, which is what makes that trade safe.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class ProductChangedConsumer {

    private final ProductIndexer indexer;

    @Bean
    public Consumer<ProductChangedEvent> productChanged() {
        return event -> {
            if (event.productId() == null) {
                // Nothing addressable. Retrying cannot help, so it goes straight past rather than
                // round the retry loop three times on its way to the same place.
                log.warn("Ignoring a ProductChanged event with no product id: {}", event.eventId());
                return;
            }
            indexer.apply(event);
        };
    }
}
