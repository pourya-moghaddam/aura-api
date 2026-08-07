package com.aura.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Envelope every published event carries.
 *
 * <p>{@code eventId} exists so consumers can deduplicate. Kafka gives at-least-once delivery, so a
 * consumer that performs a side effect — sending an SMS, decrementing stock — will eventually see
 * the same event twice and must be able to recognise it.
 */
public interface DomainEvent {

    UUID eventId();

    Instant occurredAt();
}
