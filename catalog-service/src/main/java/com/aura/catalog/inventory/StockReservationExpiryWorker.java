package com.aura.catalog.inventory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sweeps up holds nobody came back for.
 *
 * <p>The safety net that makes the reservation design correct without a saga. A shopper who closes
 * the tab at the payment gateway, or an order-service that dies between reserving and verifying,
 * leaves stock held by an order that will never complete. Nothing in either service is in a
 * position to notice — so this does, on a timer, and the stock returns on its own.
 *
 * <p>Batched so one sweep cannot lock an unbounded number of rows, and delegating to
 * {@code StockReservationService} rather than doing the work here: {@code @Transactional} is proxy-
 * applied, so a self-invocation would run the claim outside a transaction and {@code SKIP LOCKED}
 * would quietly stop isolating anything. That exact mistake has already been made once in this
 * codebase, in media-service.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockReservationExpiryWorker {

    private static final int BATCH_SIZE = 100;

    /** Bounds one sweep, so a large backlog drains over several passes instead of one long lock. */
    private static final int MAX_BATCHES_PER_RUN = 10;

    private final StockReservationService stockReservationService;

    @Scheduled(fixedDelayString = "${aura.catalog.inventory.expiry-sweep-interval:PT30S}")
    public void releaseExpiredReservations() {
        int total = 0;
        for (int pass = 0; pass < MAX_BATCHES_PER_RUN; pass++) {
            int released = stockReservationService.releaseExpired(BATCH_SIZE);
            total += released;
            if (released < BATCH_SIZE) {
                break;
            }
        }

        if (total > 0) {
            log.info("Expiry sweep returned {} reservation(s) worth of stock to the shelf", total);
        }
    }
}
