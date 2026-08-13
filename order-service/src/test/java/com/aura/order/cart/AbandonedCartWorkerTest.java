package com.aura.order.cart;

import com.aura.order.config.CartProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * The sweep delegates to {@code CartService} rather than deleting here, because
 * {@code @Transactional} is proxy-applied and a self-invocation would run the delete outside a
 * transaction. Same shape as the media and stock workers, and the same mistake avoided.
 */
@ExtendWith(MockitoExtension.class)
class AbandonedCartWorkerTest {

    @Mock
    private CartService cartService;

    @Test
    @DisplayName("carts untouched for longer than the retention window are swept")
    void sweepsPastTheRetentionWindow() {
        AbandonedCartWorker worker = new AbandonedCartWorker(cartService,
            new CartProperties(Duration.ofDays(30), false));
        OffsetDateTime before = OffsetDateTime.now().minusDays(30);

        worker.sweepAbandonedGuestCarts();

        ArgumentCaptor<OffsetDateTime> captor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(cartService).sweepAbandonedGuestCarts(captor.capture());
        // Within a second of thirty days ago - the exact instant is not the point, the window is.
        assertThat(captor.getValue()).isCloseTo(before, within(java.time.temporal.ChronoUnit.SECONDS, 5));
    }

    @Test
    @DisplayName("a shorter retention window moves the cutoff, so the setting is actually read")
    void retentionWindowIsHonoured() {
        AbandonedCartWorker worker = new AbandonedCartWorker(cartService,
            new CartProperties(Duration.ofDays(1), false));

        worker.sweepAbandonedGuestCarts();

        ArgumentCaptor<OffsetDateTime> captor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(cartService).sweepAbandonedGuestCarts(captor.capture());
        assertThat(captor.getValue()).isAfter(OffsetDateTime.now().minusDays(2));
    }

    private static org.assertj.core.data.TemporalUnitOffset within(
        java.time.temporal.ChronoUnit unit, long amount) {
        return new org.assertj.core.data.TemporalUnitWithinOffset(amount, unit);
    }
}
