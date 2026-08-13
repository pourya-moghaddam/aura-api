package com.aura.order.payment;

import com.aura.common.events.OrderPaidEvent;
import com.aura.common.events.Topics;
import com.aura.common.web.error.BusinessRuleException;
import com.aura.order.catalog.CatalogGateway;
import com.aura.order.order.Order;
import com.aura.order.order.OrderItem;
import com.aura.order.order.OrderItemRepository;
import com.aura.order.order.OrderRepository;
import com.aura.order.order.PaymentStatus;
import com.aura.order.payment.dto.PaymentStartResponse;
import com.aura.order.payment.zarinpal.ZarinpalClient;
import com.aura.order.payment.zarinpal.ZarinpalProperties;
import com.aura.order.payment.zarinpal.ZarinpalUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the shop believes about a payment, and why.
 *
 * <p>Three rules are under test, and each one is a way of losing money or a customer's trust:
 * {@code Status=OK} is not evidence, 101 is a success, and an unreachable gateway is not a
 * failure. Every test below is one of those three said out loud.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentServiceTest {

    private static final String AUTHORITY = "S0000001";

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentEventRepository paymentEventRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private CatalogGateway catalogGateway;

    @Mock
    private ZarinpalClient zarinpalClient;

    @Mock
    private OrderItemRepository orderItemRepository;

    @Mock
    private com.aura.order.outbox.OutboxWriter outboxWriter;

    private PaymentService paymentService;
    private Order order;
    private Payment payment;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(paymentRepository, paymentEventRepository,
            orderRepository, catalogGateway, zarinpalClient,
            new ZarinpalProperties("m", "https://sandbox.zarinpal.com", "http://cb",
                "http://shop/result", "http"),
            orderItemRepository, outboxWriter);

        order = new Order();
        order.setId(99L);
        order.setTraceCode("ABCDEFGHJK");
        order.setTotal(1_040_000L);
        order.setBuyerPhone("+989121234567");
        order.setPaymentStatus(PaymentStatus.PENDING);

        payment = Payment.forOrder(99L, 1_040_000L);
        payment.setId(5L);
        payment.setAuthority(AUTHORITY);

        when(orderRepository.findByTraceCode("ABCDEFGHJK")).thenReturn(Optional.of(order));
        when(orderRepository.findById(99L)).thenReturn(Optional.of(order));
        when(orderRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(paymentRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(paymentRepository.lockByAuthority(AUTHORITY)).thenReturn(Optional.of(payment));
    }

    private ZarinpalClient.VerifyResult verified(int code) {
        return new ZarinpalClient.VerifyResult(code, "201", "502229******5995", "Verified", "{}");
    }

    @Nested
    @DisplayName("starting a payment")
    class Starting {

        @Test
        @DisplayName("the authority is stored and the shopper is sent to StartPay")
        void startsAnAttempt() {
            when(paymentRepository.save(any())).thenAnswer(i -> {
                Payment saved = i.getArgument(0);
                saved.setId(5L);
                return saved;
            });
            when(zarinpalClient.request(anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn(new ZarinpalClient.RequestResult(100, AUTHORITY, "Success", "{}"));

            PaymentStartResponse response = paymentService.start("abcdefghjk");

            assertThat(response.authority()).isEqualTo(AUTHORITY);
            assertThat(response.redirectUrl())
                .isEqualTo("https://sandbox.zarinpal.com/pg/StartPay/" + AUTHORITY);
            assertThat(response.amount()).isEqualTo(1_040_000L);
        }

        @Test
        @DisplayName("the gateway is asked for the order's total, not for a figure from outside")
        void asksForTheOrderTotal() {
            when(zarinpalClient.request(anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn(new ZarinpalClient.RequestResult(100, AUTHORITY, "Success", "{}"));

            paymentService.start("ABCDEFGHJK");

            verify(zarinpalClient).request(org.mockito.ArgumentMatchers.eq(1_040_000L),
                anyString(), anyString(), any(), any());
        }

        @Test
        @DisplayName("a refused request leaves the attempt failed and says so")
        void refusedRequest() {
            when(zarinpalClient.request(anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn(new ZarinpalClient.RequestResult(-9, null, "Validation error", "{}"));

            assertThatThrownBy(() -> paymentService.start("ABCDEFGHJK"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("could not start");
        }

        @Test
        @DisplayName("an order already paid for cannot be paid for again")
        void alreadyPaid() {
            order.setPaymentStatus(PaymentStatus.PAID);

            assertThatThrownBy(() -> paymentService.start("ABCDEFGHJK"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("already been paid");
            verify(zarinpalClient, never()).request(anyLong(), anyString(), anyString(), any(), any());
        }

        @Test
        @DisplayName("an expired order cannot be paid for at all")
        void expiredOrder() {
            // Its stock went back on the shelf when the hold lapsed. Taking money now would be
            // taking it for something that can no longer be shipped.
            order.setPaymentStatus(PaymentStatus.EXPIRED);

            assertThatThrownBy(() -> paymentService.start("ABCDEFGHJK"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("no longer be paid");
        }
    }

    @Nested
    @DisplayName("the callback")
    class Callback {

        @Test
        @DisplayName("Status=OK is verified server-side before anything is believed")
        void okIsVerified() {
            // The single most important line in this file. Status=OK is a query parameter in the
            // shopper's own browser; treating it as proof is the classic way to give goods away.
            when(zarinpalClient.verify(1_040_000L, AUTHORITY)).thenReturn(verified(100));

            paymentService.settleCallback(AUTHORITY, "OK");

            verify(zarinpalClient).verify(1_040_000L, AUTHORITY);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
            assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
        }

        @Test
        @DisplayName("a verified payment records the reference and commits the stock")
        void verifiedPaymentSettles() {
            when(zarinpalClient.verify(anyLong(), anyString())).thenReturn(verified(100));

            paymentService.settleCallback(AUTHORITY, "OK");

            assertThat(payment.getRefId()).isEqualTo("201");
            assertThat(payment.getCardPanMasked()).isEqualTo("502229******5995");
            assertThat(payment.getVerifiedAt()).isNotNull();
            assertThat(order.getPaidAt()).isNotNull();
            verify(catalogGateway).commitStock(99L);
        }

        @Test
        @DisplayName("a paid order announces what sold")
        void paidOrderPublishesWhatSold() {
            when(zarinpalClient.verify(anyLong(), anyString())).thenReturn(verified(100));
            OrderItem item = new OrderItem();
            item.setProductId(7L);
            item.setVariantId(70L);
            item.setQuantity(3);
            when(orderItemRepository.findByOrderIdOrderByIdAsc(99L)).thenReturn(List.of(item));

            paymentService.settleCallback(AUTHORITY, "OK");

            ArgumentCaptor<OrderPaidEvent> captor = ArgumentCaptor.forClass(OrderPaidEvent.class);
            verify(outboxWriter).write(eq(Topics.ORDER_PAID), eq("99"), captor.capture());
            assertThat(captor.getValue().lines())
                .containsExactly(new OrderPaidEvent.Line(7L, 70L, 3));
        }

        @Test
        @DisplayName("a failed payment announces nothing")
        void failedPaymentPublishesNothing() {
            // Popularity follows money. Publishing on the attempt would let anyone inflate a
            // product's ranking by starting checkouts they never pay for.
            when(zarinpalClient.verify(anyLong(), anyString())).thenReturn(verified(-51));

            paymentService.settleCallback(AUTHORITY, "OK");

            verify(outboxWriter, never()).write(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("101 settles the order exactly as 100 does")
        void alreadyVerifiedStillPays() {
            // Zarinpal answers 101 for every verify after the first. Reading it as a failure
            // cancels an order that was paid for and releases the stock underneath the customer.
            when(zarinpalClient.verify(anyLong(), anyString())).thenReturn(verified(101));

            paymentService.settleCallback(AUTHORITY, "OK");

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
            assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
            verify(catalogGateway).commitStock(99L);
            verify(catalogGateway, never()).releaseStock(anyLong());
        }

        @Test
        @DisplayName("a refused verification fails the order and puts the stock back")
        void refusedVerification() {
            when(zarinpalClient.verify(anyLong(), anyString()))
                .thenReturn(new ZarinpalClient.VerifyResult(-51, null, null, "Invalid", "{}"));

            paymentService.settleCallback(AUTHORITY, "OK");

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.FAILED);
            verify(catalogGateway).releaseStock(99L);
            verify(catalogGateway, never()).commitStock(anyLong());
        }

        @Test
        @DisplayName("Status=NOK cancels without asking the gateway")
        void nokCancels() {
            // Taken at face value only because it can cost the shop nothing: the worst case is
            // releasing stock for a payment that did not happen.
            paymentService.settleCallback(AUTHORITY, "NOK");

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
            assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.CANCELLED);
            verify(zarinpalClient, never()).verify(anyLong(), anyString());
            verify(catalogGateway).releaseStock(99L);
        }

        @Test
        @DisplayName("an unreachable gateway leaves the payment pending, not failed")
        void unreachableGatewayLeavesItPending() {
            // Silence is not failure. Marking it failed would release the stock and cancel an
            // order that may well have been paid for - and reconciliation could then never fix it,
            // because it only looks at pending payments.
            when(zarinpalClient.verify(anyLong(), anyString()))
                .thenThrow(new ZarinpalUnavailableException("down", null));

            assertThatThrownBy(() -> paymentService.settleCallback(AUTHORITY, "OK"))
                .isInstanceOf(BusinessRuleException.class);

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
            verify(catalogGateway, never()).releaseStock(anyLong());
            verify(catalogGateway, never()).commitStock(anyLong());
        }

        @Test
        @DisplayName("a repeated callback changes nothing")
        void repeatedCallbackIsHarmless() {
            // A shopper refreshing the return page. The gateway is not asked again and the order
            // is not touched.
            payment.markPaid("201", "5022");

            paymentService.settleCallback(AUTHORITY, "OK");

            verify(zarinpalClient, never()).verify(anyLong(), anyString());
            verify(catalogGateway, never()).commitStock(anyLong());
        }

        @Test
        @DisplayName("an abandoned attempt cannot cancel an order another attempt has paid for")
        void abandonedAttemptDoesNotCancelAPaidOrder() {
            // A shopper who gives up on one gateway visit and completes a second. The first
            // attempt's late NOK must not cancel the order the second one paid for.
            order.setPaymentStatus(PaymentStatus.PAID);

            paymentService.settleCallback(AUTHORITY, "NOK");

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
            assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
            verify(catalogGateway, never()).releaseStock(anyLong());
        }

        @Test
        @DisplayName("every exchange with the gateway is written down")
        void eventsAreRecorded() {
            // When a customer says they were charged and the order says otherwise, this is the
            // only record of what actually passed between the two systems.
            when(zarinpalClient.verify(anyLong(), anyString())).thenReturn(verified(100));

            paymentService.settleCallback(AUTHORITY, "OK");

            verify(paymentEventRepository, org.mockito.Mockito.times(2)).save(any());
        }
    }

    @Nested
    @DisplayName("reconciliation")
    class Reconciliation {

        @Test
        @DisplayName("a lost callback is settled by asking the gateway directly")
        void settlesALostCallback() {
            when(paymentRepository.findStaleAuthorities(any(), any(), any()))
                .thenReturn(java.util.List.of(AUTHORITY));
            when(zarinpalClient.verify(anyLong(), anyString())).thenReturn(verified(100));

            int settled = paymentService.reconcile(java.time.Duration.ofMinutes(10), 50);

            assertThat(settled).isEqualTo(1);
            assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
            verify(catalogGateway).commitStock(99L);
        }

        @Test
        @DisplayName("the sweep reads the payment under the lock, never from a stale copy")
        void readsUnderTheLock() {
            // The sweep must take only keys from its search and load the row under the lock. If it
            // carried loaded entities, the persistence context would hand the same stale instance
            // back from the lock query and a payment settled in between would be settled again -
            // committing the stock twice. This is the shape of that fix, pinned.
            when(paymentRepository.findStaleAuthorities(any(), any(), any()))
                .thenReturn(java.util.List.of(AUTHORITY));
            when(zarinpalClient.verify(anyLong(), anyString())).thenReturn(verified(100));

            paymentService.reconcile(java.time.Duration.ofMinutes(10), 50);

            verify(paymentRepository).lockByAuthority(AUTHORITY);
        }

        @Test
        @DisplayName("a payment the callback settled first is skipped")
        void skipsAlreadySettled() {
            // The race that actually happens: the shopper's callback and the sweep reaching the
            // same payment within the same second. The lock orders them; this check stops the
            // second doing the work twice.
            when(paymentRepository.findStaleAuthorities(any(), any(), any()))
                .thenReturn(java.util.List.of(AUTHORITY));
            payment.markPaid("201", "5022");

            assertThat(paymentService.reconcile(java.time.Duration.ofMinutes(10), 50)).isZero();
            verify(zarinpalClient, never()).verify(anyLong(), anyString());
        }

        @Test
        @DisplayName("a gateway still down leaves the payment for the next sweep")
        void leavesUnreachablePaymentsAlone() {
            when(paymentRepository.findStaleAuthorities(any(), any(), any()))
                .thenReturn(java.util.List.of(AUTHORITY));
            when(zarinpalClient.verify(anyLong(), anyString()))
                .thenThrow(new ZarinpalUnavailableException("down", null));

            assertThat(paymentService.reconcile(java.time.Duration.ofMinutes(10), 50)).isZero();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        }

        @Test
        @DisplayName("one unreachable payment does not stop the sweep")
        void oneFailureDoesNotStopTheBatch() {
            Payment second = Payment.forOrder(99L, 1_040_000L);
            second.setId(6L);
            second.setAuthority("S0000002");
            when(paymentRepository.findStaleAuthorities(any(), any(), any()))
                .thenReturn(java.util.List.of(AUTHORITY, "S0000002"));
            when(paymentRepository.lockByAuthority("S0000002")).thenReturn(Optional.of(second));
            when(zarinpalClient.verify(anyLong(), org.mockito.ArgumentMatchers.eq(AUTHORITY)))
                .thenThrow(new ZarinpalUnavailableException("down", null));
            when(zarinpalClient.verify(anyLong(), org.mockito.ArgumentMatchers.eq("S0000002")))
                .thenReturn(verified(100));

            assertThat(paymentService.reconcile(java.time.Duration.ofMinutes(10), 50)).isEqualTo(1);
        }

        @Test
        @DisplayName("a payment the gateway says was never completed is failed and its stock released")
        void failsWhatWasNeverPaid() {
            when(paymentRepository.findStaleAuthorities(any(), any(), any()))
                .thenReturn(java.util.List.of(AUTHORITY));
            when(zarinpalClient.verify(anyLong(), anyString()))
                .thenReturn(new ZarinpalClient.VerifyResult(-51, null, null, "Invalid", "{}"));

            paymentService.reconcile(java.time.Duration.ofMinutes(10), 50);

            assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.FAILED);
            verify(catalogGateway).releaseStock(99L);
        }
    }
}
