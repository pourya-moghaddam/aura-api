package com.aura.order.payment;

import com.aura.order.config.PaymentReconciliationProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Asks the gateway about payments nobody came back from.
 *
 * <p>Not an optimisation. Callbacks are lost routinely — the browser is closed on the bank's page,
 * the network drops on the way back — and every lost one is a customer who paid and whose order
 * says they did not. That is the worst state this system can reach and the first one a customer
 * notices.
 *
 * <p>Requires {@code @EnableScheduling} on the application class, whose absence is silent.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentReconciliationWorker {

    private final PaymentService paymentService;
    private final PaymentReconciliationProperties properties;

    @Scheduled(fixedDelayString = "${aura.order.payment-reconciliation.interval:PT2M}")
    public void reconcile() {
        try {
            paymentService.reconcile(properties.olderThan(), properties.batchSize());
        } catch (RuntimeException e) {
            // Never allowed to escape: a scheduled method that throws is silently cancelled by
            // Spring, and the sweep would stop for good after one bad night.
            log.error("Payment reconciliation sweep failed; will retry on the next tick", e);
        }
    }
}
