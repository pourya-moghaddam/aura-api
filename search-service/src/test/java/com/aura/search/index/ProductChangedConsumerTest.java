package com.aura.search.index;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The thin part. Everything interesting is in the indexer; what is worth pinning here is the one
 * message shape that must not go round the retry loop.
 */
@ExtendWith(MockitoExtension.class)
class ProductChangedConsumerTest {

    @Mock
    private ProductIndexer indexer;

    @Test
    @DisplayName("an ordinary event is handed to the indexer")
    void indexes() {
        new ProductChangedConsumer(indexer).productChanged()
            .accept(ProductDocumentTest.event(1, null));

        verify(indexer).apply(any());
    }

    @Test
    @DisplayName("an event with no product id is dropped rather than retried")
    void skipsUnaddressableEvents() {
        // Retrying cannot help - there is nothing to address - so three attempts and a
        // dead-letter hop would only add delay on the way to the same outcome.
        var noId = new com.aura.common.events.ProductChangedEvent(
            UUID.randomUUID(), Instant.now(), null, 9L, 3L, List.of(), List.of(),
            "x", "x", null, "ACTIVE", 1L, 1L, 1, Map.of(), List.of(), List.of(),
            null, Instant.now(), 1L, false);

        new ProductChangedConsumer(indexer).productChanged().accept(noId);

        verify(indexer, never()).apply(any());
    }
}
