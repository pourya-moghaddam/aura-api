package com.aura.catalog.outbox;

import com.aura.common.events.DomainEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records an event for publication, inside the caller's transaction.
 *
 * <p>{@code MANDATORY} on purpose. The guarantee this class exists to provide is that the event
 * and the state change it describes commit together; being called outside a transaction would
 * silently give none of that, and the failure would look like an event that occasionally goes
 * missing under load. Refusing to run is a far better outcome than appearing to work.
 */
@Component
@RequiredArgsConstructor
public class OutboxWriter {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void write(String topic, String partitionKey, DomainEvent event) {
        try {
            outboxRepository.save(OutboxEntry.pending(
                event.eventId(), topic, partitionKey, objectMapper.writeValueAsString(event)));
        } catch (JsonProcessingException e) {
            // Serialisation failing means the event type is wrong, not that the broker is down.
            // Letting it roll the transaction back is correct: the state change should not stand
            // if its event can never be sent.
            throw new IllegalStateException(
                "Could not serialise " + event.getClass().getSimpleName() + " for the outbox", e);
        }
    }
}
