package com.aura.notification.delivery;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SmsDeliveryRepository extends JpaRepository<SmsDelivery, Long> {

    Optional<SmsDelivery> findByDedupeKey(String dedupeKey);

    Optional<SmsDelivery> findByEventId(UUID eventId);
}
