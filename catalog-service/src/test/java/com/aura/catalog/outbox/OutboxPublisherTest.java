package com.aura.catalog.outbox;

import com.aura.common.events.DomainEvent;
import com.aura.common.events.ProductChangedEvent;
import com.aura.common.events.Topics;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.Message;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The outbox exists to stop a broker failure and a database commit disagreeing. These cover the
 * two halves of that: an event is written with the state change, and a failure to send leaves the
 * row pending rather than losing it.
 */
@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private StreamBridge streamBridge;

    private OutboxPublisher publisher;
    private OutboxWriter writer;

    @BeforeEach
    void setUp() {
        publisher = new OutboxPublisher(outboxRepository, streamBridge);
        // Mirrors what Boot injects. A bare ObjectMapper cannot serialise the Instant on every
        // DomainEvent, so using one here would test a mapper the application never has.
        writer = new OutboxWriter(outboxRepository,
            JsonMapper.builder().addModule(new JavaTimeModule()).build());
    }

    private OutboxEntry entry(long id, String key) {
        OutboxEntry e = OutboxEntry.pending(UUID.randomUUID(), "aura.catalog.product-changed.v1",
            key, "{\"productId\":1}");
        e.setId(id);
        return e;
    }

    @Test
    @DisplayName("a pending event is sent and marked published")
    void publishesAndMarks() {
        OutboxEntry e = entry(1L, "42");
        when(outboxRepository.claimPending(anyInt())).thenReturn(List.of(e));
        when(streamBridge.send(anyString(), any(Message.class))).thenReturn(true);

        assertThat(publisher.publishBatch(100)).isEqualTo(1);
        assertThat(e.getPublishedAt()).isNotNull();
    }

    @Test
    @DisplayName("the partition key travels with the message, so one product stays ordered")
    void partitionKeyIsSet() {
        // Without it Kafka round-robins, and a redelivered older document can be indexed after a
        // newer one - leaving search showing a price the product no longer has.
        OutboxEntry e = entry(1L, "42");
        when(outboxRepository.claimPending(anyInt())).thenReturn(List.of(e));
        when(streamBridge.send(anyString(), any(Message.class))).thenReturn(true);

        publisher.publishBatch(100);

        ArgumentCaptor<Message<?>> captor = ArgumentCaptor.captor();
        verify(streamBridge).send(anyString(), captor.capture());
        assertThat(captor.getValue().getHeaders()).containsEntry("partitionKey", "42");
        assertThat(captor.getValue().getHeaders()).containsKey("eventId");
    }

    @Test
    @DisplayName("a send failure leaves the row pending, with the reason recorded")
    void failureKeepsTheRowPending() {
        // The whole point: losing the event here would leave the catalogue and the index
        // permanently disagreeing, with nothing to say so.
        OutboxEntry e = entry(1L, "42");
        when(outboxRepository.claimPending(anyInt())).thenReturn(List.of(e));
        when(streamBridge.send(anyString(), any(Message.class)))
            .thenThrow(new RuntimeException("broker unreachable"));

        assertThat(publisher.publishBatch(100)).isZero();
        assertThat(e.getPublishedAt()).isNull();
        assertThat(e.getAttempts()).isEqualTo(1);
        assertThat(e.getLastError()).contains("broker unreachable");
    }

    @Test
    @DisplayName("one failing event does not block the ones behind it")
    void oneFailureDoesNotBlockTheBatch() {
        OutboxEntry bad = entry(1L, "1");
        OutboxEntry good = entry(2L, "2");
        when(outboxRepository.claimPending(anyInt())).thenReturn(List.of(bad, good));
        when(streamBridge.send(anyString(), any(Message.class)))
            .thenThrow(new RuntimeException("poison"))
            .thenReturn(true);

        assertThat(publisher.publishBatch(100)).isEqualTo(1);
        assertThat(bad.getPublishedAt()).isNull();
        assertThat(good.getPublishedAt()).isNotNull();
    }

    @Test
    @DisplayName("repeated failures accumulate, so a permanently stuck event becomes visible")
    void attemptsAccumulate() {
        OutboxEntry e = entry(1L, "42");
        e.markFailed("first");
        e.markFailed("second");

        assertThat(e.getAttempts()).isEqualTo(2);
        assertThat(e.getLastError()).isEqualTo("second");
    }

    @Test
    @DisplayName("a very long error is truncated rather than stored whole")
    void longErrorsTruncated() {
        OutboxEntry e = entry(1L, "42");
        e.markFailed("x".repeat(5000));

        assertThat(e.getLastError()).hasSize(1000);
    }

    @Test
    @DisplayName("an empty outbox does no work")
    void emptyOutbox() {
        when(outboxRepository.claimPending(anyInt())).thenReturn(List.of());

        assertThat(publisher.publishBatch(100)).isZero();
        verifyNoInteractions(streamBridge);
        verify(outboxRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("the writer stores the serialised event for later")
    void writerStoresTheEvent() {
        ProductChangedEvent event = new ProductChangedEvent(
            UUID.randomUUID(), Instant.now(), 42L, 7L, 5L, List.of(1L, 5L),
            List.of("Clothing", "Shirts"),
            "Oxford Shirt", "oxford-shirt", "A shirt", "ACTIVE", 250_000L, 250_000L, null, 4,
            Map.of("material", List.of("cotton")), List.of("Navy"), List.of("L"),
            UUID.randomUUID(), Instant.parse("2026-01-01T00:00:00Z"),
            1_700_000_000_000L, false);

        writer.write(Topics.PRODUCT_CHANGED, event.partitionKey(), event);

        ArgumentCaptor<OutboxEntry> captor = ArgumentCaptor.forClass(OutboxEntry.class);
        verify(outboxRepository).save(captor.capture());
        assertThat(captor.getValue().getTopic()).isEqualTo(Topics.PRODUCT_CHANGED);
        // Keyed by product so every event for it lands on one partition and stays ordered.
        assertThat(captor.getValue().getPartitionKey()).isEqualTo("42");
        assertThat(captor.getValue().getPublishedAt()).isNull();
        // The whole document travels, so search-service never has to call back for it.
        assertThat(captor.getValue().getPayload())
            .contains("oxford-shirt").contains("cotton").contains("Navy");
    }

    @Test
    @DisplayName("an unserialisable event fails the transaction rather than being silently dropped")
    void unserialisableEventThrows() {
        // If the event cannot be sent, the state change it describes should not stand either.
        record Unserialisable(UUID eventId, Instant occurredAt, Object self) implements DomainEvent {
        }
        Unserialisable broken = new Unserialisable(UUID.randomUUID(), Instant.now(), new Object());

        assertThatThrownBy(() -> writer.write("t", "k", broken))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("outbox");
    }

    @Test
    @DisplayName("published events older than the retention window are purged")
    void purgesOldEvents() {
        when(outboxRepository.deletePublishedBefore(any())).thenReturn(7);

        assertThat(publisher.purgePublishedBefore(OffsetDateTime.now().minusDays(7))).isEqualTo(7);
    }

    @Test
    @DisplayName("the backlog is countable, so a stalled outbox can be alerted on")
    void backlogIsCountable() {
        when(outboxRepository.countByPublishedAtIsNull()).thenReturn(42L);

        assertThat(publisher.pendingCount()).isEqualTo(42L);
    }
}
