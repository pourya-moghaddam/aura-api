package com.aura.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param olderThan how long a payment may sit pending before the gateway is asked about it
 *                  directly. Long enough that an ordinary shopper is still typing their card
 *                  details, short enough to fix a lost callback before they complain.
 * @param batchSize a bound per sweep, so a backlog is worked through steadily rather than in one
 *                  long transaction holding locks.
 */
@ConfigurationProperties(prefix = "aura.order.payment-reconciliation")
public record PaymentReconciliationProperties(
    @DefaultValue("PT10M") Duration olderThan,
    @DefaultValue("50") int batchSize
) {
}
