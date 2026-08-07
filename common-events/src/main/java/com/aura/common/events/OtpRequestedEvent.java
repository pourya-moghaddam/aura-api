package com.aura.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Emitted by auth-service when a one-time code needs to reach a phone.
 *
 * <p>Carries the code and the reason, not a rendered message: message copy and template selection
 * belong to notification-service, which is the only thing that knows about SMS providers. The
 * previous version of this event carried a pre-rendered {@code message} that the sms.ir integration
 * never read, because that provider renders from a server-side template ID.
 *
 * @param phone recipient in E.164 form
 * @param code  plaintext one-time code — only ever in transit, never persisted in this shape
 */
public record OtpRequestedEvent(
    UUID eventId,
    Instant occurredAt,
    String phone,
    String code,
    OtpPurpose purpose
) implements DomainEvent {

    public static OtpRequestedEvent of(String phone, String code, OtpPurpose purpose) {
        return new OtpRequestedEvent(UUID.randomUUID(), Instant.now(), phone, code, purpose);
    }
}
