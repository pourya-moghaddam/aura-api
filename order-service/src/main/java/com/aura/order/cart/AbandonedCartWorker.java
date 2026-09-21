package com.aura.order.cart;

import com.aura.order.config.CartProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * Reclaims guest carts nobody came back for.
 *
 * <p>A scheduled job rather than a TTL, because carts live in PostgreSQL precisely so they are not
 * at the mercy of an eviction policy. Untouched guest carts only: a signed-in shopper's basket is
 * theirs to keep, and deleting it after a quiet month is losing their work, not housekeeping.
 *
 * <p>Delegates to {@code CartService} rather than doing the delete here — {@code @Transactional} is
 * proxy-applied, and a self-invocation would run the delete outside a transaction.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AbandonedCartWorker {

    private final CartService cartService;
    private final CartProperties cartProperties;

    /** Daily. Nothing depends on the timing, and the rows are harmless until they are removed. */
    @Scheduled(cron = "${aura.order.cart.sweep-cron:0 15 3 * * *}")
    public void sweepAbandonedGuestCarts() {
        cartService.sweepAbandonedGuestCarts(
            OffsetDateTime.now().minus(cartProperties.guestRetention()));
    }
}
