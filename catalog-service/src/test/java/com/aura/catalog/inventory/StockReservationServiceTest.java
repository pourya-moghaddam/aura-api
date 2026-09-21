package com.aura.catalog.inventory;

import com.aura.catalog.inventory.StockReservation.ReservationStatus;
import com.aura.catalog.inventory.dto.ReservationResponse;
import com.aura.catalog.inventory.dto.ReserveStockRequest;
import com.aura.common.web.error.BusinessRuleException;
import com.aura.common.web.error.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The arithmetic and the idempotency. The locking that makes it safe under concurrency cannot be
 * tested with mocks at all — see {@code StockReservationConcurrencyIT}, which races real
 * transactions against a real PostgreSQL.
 */
@ExtendWith(MockitoExtension.class)
class StockReservationServiceTest {

    private static final long ORDER = 500L;
    private static final long VARIANT_A = 1L;
    private static final long VARIANT_B = 2L;

    @Mock
    private InventoryRepository inventoryRepository;

    @Mock
    private StockReservationRepository stockReservationRepository;

    private StockReservationService service;

    @BeforeEach
    void setUp() {
        service = new StockReservationService(inventoryRepository, stockReservationRepository);
    }

    private Inventory inventory(long variantId, int onHand, int reserved) {
        Inventory inventory = Inventory.forVariant(variantId, onHand);
        inventory.setQuantityReserved(reserved);
        return inventory;
    }

    private StockReservation held(long variantId, int quantity) {
        StockReservation reservation = StockReservation.hold(
            variantId, ORDER, quantity, OffsetDateTime.now().plusMinutes(15));
        reservation.setId(variantId * 10);
        return reservation;
    }

    private ReserveStockRequest request(Object... variantAndQuantity) {
        List<ReserveStockRequest.Line> lines = new java.util.ArrayList<>();
        for (int i = 0; i < variantAndQuantity.length; i += 2) {
            lines.add(new ReserveStockRequest.Line(
                (Long) variantAndQuantity[i], (Integer) variantAndQuantity[i + 1]));
        }
        return new ReserveStockRequest(ORDER, lines);
    }

    private void noExistingReservation() {
        lenient().when(stockReservationRepository.lockOrder(anyLong())).thenReturn(1);
        when(stockReservationRepository.findByOrderIdAndStatus(ORDER, ReservationStatus.HELD))
            .thenReturn(List.of());
        when(stockReservationRepository.existsByOrderIdAndStatus(ORDER, ReservationStatus.COMMITTED))
            .thenReturn(false);
    }

    private void echoSaveReservations() {
        when(stockReservationRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Nested
    class Reserving {

        @Test
        @DisplayName("a hold moves stock into reserved without touching on-hand")
        void holdIncrementsReserved() {
            // The units are still physically there; they are just spoken for. Decrementing on-hand
            // now would lose them the moment the shopper abandons the checkout.
            Inventory a = inventory(VARIANT_A, 10, 0);
            noExistingReservation();
            when(inventoryRepository.lockForUpdate(any())).thenReturn(List.of(a));
            echoSaveReservations();

            service.reserve(request(VARIANT_A, 3));

            assertThat(a.getQuantityReserved()).isEqualTo(3);
            assertThat(a.getQuantityOnHand()).isEqualTo(10);
            assertThat(a.available()).isEqualTo(7);
        }

        @Test
        @DisplayName("the order is locked before the idempotency check it protects")
        void orderLockedBeforeTheCheck() {
            Inventory a = inventory(VARIANT_A, 10, 0);
            noExistingReservation();
            when(inventoryRepository.lockForUpdate(any())).thenReturn(List.of(a));
            echoSaveReservations();

            service.reserve(request(VARIANT_A, 1));

            // Taking the lock after the check would leave the race wide open.
            var inOrder = inOrder(stockReservationRepository);
            inOrder.verify(stockReservationRepository).lockOrder(ORDER);
            inOrder.verify(stockReservationRepository).findByOrderIdAndStatus(ORDER, ReservationStatus.HELD);
        }

        @Test
        @DisplayName("rows are locked before anything is changed")
        void locksBeforeMutating() {
            Inventory a = inventory(VARIANT_A, 10, 0);
            noExistingReservation();
            when(inventoryRepository.lockForUpdate(any())).thenReturn(List.of(a));
            echoSaveReservations();

            service.reserve(request(VARIANT_A, 1));

            var inOrder = inOrder(inventoryRepository, stockReservationRepository);
            inOrder.verify(inventoryRepository).lockForUpdate(any());
            inOrder.verify(inventoryRepository).saveAll(any());
            inOrder.verify(stockReservationRepository).saveAll(any());
        }

        @Test
        @DisplayName("a request for more than is available is refused")
        void insufficientStockRefused() {
            noExistingReservation();
            when(inventoryRepository.lockForUpdate(any()))
                .thenReturn(List.of(inventory(VARIANT_A, 5, 3)));

            assertThatThrownBy(() -> service.reserve(request(VARIANT_A, 3)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("only 2 left");
        }

        @Test
        @DisplayName("availability counts what other orders are holding, not just what exists")
        void reservedStockIsNotAvailable() {
            // 10 on the shelf but 9 held by orders being paid for means one is buyable, not ten.
            noExistingReservation();
            when(inventoryRepository.lockForUpdate(any()))
                .thenReturn(List.of(inventory(VARIANT_A, 10, 9)));

            assertThatThrownBy(() -> service.reserve(request(VARIANT_A, 2)))
                .isInstanceOf(BusinessRuleException.class);
        }

        @Test
        @DisplayName("a multi-line cart is all or nothing")
        void partialFulfilmentRefused() {
            // Reserving what fits would leave order-service holding a half-filled order nobody
            // asked for, which it would then have to unpick.
            noExistingReservation();
            when(inventoryRepository.lockForUpdate(any()))
                .thenReturn(List.of(inventory(VARIANT_A, 10, 0), inventory(VARIANT_B, 1, 0)));

            assertThatThrownBy(() -> service.reserve(request(VARIANT_A, 2, VARIANT_B, 5)))
                .isInstanceOf(BusinessRuleException.class);

            verify(stockReservationRepository, never()).saveAll(any());
            verify(inventoryRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("a variant with no stock row is unavailable, not an error")
        void unstockedVariantIsUnavailable() {
            noExistingReservation();
            when(inventoryRepository.lockForUpdate(any())).thenReturn(List.of());

            assertThatThrownBy(() -> service.reserve(request(VARIANT_A, 1)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("not available");
        }

        @Test
        @DisplayName("reserving twice for one order returns the existing hold, it does not take more")
        void reserveIsIdempotent() {
            // A double-clicked checkout button, or a retry after a timeout, must not consume the
            // stock twice. Sequentially, this check is enough; concurrently it is not, which is
            // what the advisory lock and StockReservationConcurrencyIT are for.
            lenient().when(stockReservationRepository.lockOrder(anyLong())).thenReturn(1);
            when(stockReservationRepository.findByOrderIdAndStatus(ORDER, ReservationStatus.HELD))
                .thenReturn(List.of(held(VARIANT_A, 3)));

            ReservationResponse response = service.reserve(request(VARIANT_A, 3));

            assertThat(response.held()).hasSize(1);
            verify(inventoryRepository, never()).lockForUpdate(any());
            verify(stockReservationRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("an already-committed order cannot reserve again")
        void committedOrderCannotReserveAgain() {
            // The stock is gone. Taking it again would sell the same units twice.
            lenient().when(stockReservationRepository.lockOrder(anyLong())).thenReturn(1);
            when(stockReservationRepository.findByOrderIdAndStatus(ORDER, ReservationStatus.HELD))
                .thenReturn(List.of());
            when(stockReservationRepository.existsByOrderIdAndStatus(ORDER, ReservationStatus.COMMITTED))
                .thenReturn(true);

            assertThatThrownBy(() -> service.reserve(request(VARIANT_A, 1)))
                .isInstanceOf(ConflictException.class);
        }

        @Test
        @DisplayName("a cart listing one variant twice reserves the total once")
        void repeatedLinesAreMerged() {
            Inventory a = inventory(VARIANT_A, 10, 0);
            noExistingReservation();
            when(inventoryRepository.lockForUpdate(any())).thenReturn(List.of(a));
            echoSaveReservations();

            ReservationResponse response = service.reserve(request(VARIANT_A, 2, VARIANT_A, 3));

            assertThat(a.getQuantityReserved()).isEqualTo(5);
            assertThat(response.held()).hasSize(1);
        }

        @Test
        @DisplayName("the hold expires, and the caller is told when")
        void holdHasAnExpiry() {
            Inventory a = inventory(VARIANT_A, 10, 0);
            noExistingReservation();
            when(inventoryRepository.lockForUpdate(any())).thenReturn(List.of(a));
            echoSaveReservations();

            ReservationResponse response = service.reserve(request(VARIANT_A, 1));

            assertThat(response.expiresAt()).isAfter(OffsetDateTime.now().plusMinutes(14));
        }
    }

    @Nested
    class Committing {

        @Test
        @DisplayName("commit drops both counters — the units are gone, not merely spoken for")
        void commitDecrementsBoth() {
            // Forgetting the reserved side makes every completed sale permanently shrink what the
            // variant appears to have available, which nothing would flag.
            Inventory a = inventory(VARIANT_A, 10, 3);
            when(stockReservationRepository.findByOrderIdAndStatus(ORDER, ReservationStatus.HELD))
                .thenReturn(List.of(held(VARIANT_A, 3)));
            when(inventoryRepository.lockForUpdate(any())).thenReturn(List.of(a));

            service.commit(ORDER);

            assertThat(a.getQuantityOnHand()).isEqualTo(7);
            assertThat(a.getQuantityReserved()).isZero();
            assertThat(a.available()).isEqualTo(7);
        }

        @Test
        @DisplayName("committing an already-committed order is a no-op, not an error")
        void commitIsIdempotent() {
            when(stockReservationRepository.findByOrderIdAndStatus(ORDER, ReservationStatus.HELD))
                .thenReturn(List.of());
            when(stockReservationRepository.existsByOrderIdAndStatus(ORDER, ReservationStatus.COMMITTED))
                .thenReturn(true);

            assertThatCode(() -> service.commit(ORDER)).doesNotThrowAnyException();
            verify(inventoryRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("committing an expired hold fails loudly rather than silently doing nothing")
        void commitAfterExpiryFails() {
            // A shopper who pays at minute sixteen is a real case, and order-service has to know
            // it happened rather than believing the sale went through.
            when(stockReservationRepository.findByOrderIdAndStatus(ORDER, ReservationStatus.HELD))
                .thenReturn(List.of());
            when(stockReservationRepository.existsByOrderIdAndStatus(ORDER, ReservationStatus.COMMITTED))
                .thenReturn(false);

            assertThatThrownBy(() -> service.commit(ORDER))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("may have expired");
        }

        @Test
        @DisplayName("the reservation is marked committed and stamped")
        void reservationSettled() {
            StockReservation reservation = held(VARIANT_A, 2);
            when(stockReservationRepository.findByOrderIdAndStatus(ORDER, ReservationStatus.HELD))
                .thenReturn(List.of(reservation));
            when(inventoryRepository.lockForUpdate(any()))
                .thenReturn(List.of(inventory(VARIANT_A, 5, 2)));

            service.commit(ORDER);

            assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.COMMITTED);
            assertThat(reservation.getSettledAt()).isNotNull();
        }
    }

    @Nested
    class Releasing {

        @Test
        @DisplayName("release returns the stock to the shelf, on-hand untouched")
        void releaseDecrementsReservedOnly() {
            Inventory a = inventory(VARIANT_A, 10, 3);
            when(stockReservationRepository.findByOrderIdAndStatus(ORDER, ReservationStatus.HELD))
                .thenReturn(List.of(held(VARIANT_A, 3)));
            when(inventoryRepository.lockForUpdate(any())).thenReturn(List.of(a));

            service.release(ORDER);

            assertThat(a.getQuantityOnHand()).isEqualTo(10);
            assertThat(a.getQuantityReserved()).isZero();
            assertThat(a.available()).isEqualTo(10);
        }

        @Test
        @DisplayName("releasing nothing is a no-op — the sweep may have got there first")
        void releaseIsIdempotent() {
            when(stockReservationRepository.findByOrderIdAndStatus(ORDER, ReservationStatus.HELD))
                .thenReturn(List.of());

            assertThatCode(() -> service.release(ORDER)).doesNotThrowAnyException();
            verify(inventoryRepository, never()).saveAll(any());
        }
    }

    @Nested
    class Expiry {

        @Test
        @DisplayName("the sweep releases holds nobody came back for")
        void expiredHoldsReleased() {
            // The safety net: without this, an order-service that died mid-checkout holds the
            // stock forever and nothing in either service is positioned to notice.
            Inventory a = inventory(VARIANT_A, 10, 4);
            StockReservation stale = held(VARIANT_A, 4);
            when(stockReservationRepository.claimExpired(any(), eq(100))).thenReturn(List.of(stale));
            when(inventoryRepository.lockForUpdate(any())).thenReturn(List.of(a));

            int released = service.releaseExpired(100);

            assertThat(released).isEqualTo(1);
            assertThat(a.getQuantityReserved()).isZero();
            assertThat(stale.getStatus()).isEqualTo(ReservationStatus.RELEASED);
        }

        @Test
        @DisplayName("nothing expired means no work and no writes")
        void nothingToDo() {
            when(stockReservationRepository.claimExpired(any(), anyInt())).thenReturn(List.of());

            assertThat(service.releaseExpired(100)).isZero();
            verify(inventoryRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("a hold whose variant was deleted is still settled, so the sweep stops seeing it")
        void orphanedHoldStillSettled() {
            // Otherwise it is picked up by every sweep forever.
            StockReservation orphan = held(VARIANT_A, 2);
            when(stockReservationRepository.claimExpired(any(), anyInt())).thenReturn(List.of(orphan));
            when(inventoryRepository.lockForUpdate(any())).thenReturn(List.of());

            assertThat(service.releaseExpired(100)).isEqualTo(1);
            assertThat(orphan.getStatus()).isEqualTo(ReservationStatus.RELEASED);
        }
    }
}
