package com.aura.order.link;

import com.aura.common.web.error.BusinessRuleException;
import com.aura.common.web.error.ResourceNotFoundException;
import com.aura.order.config.OrderLinkProperties;
import com.aura.order.link.dto.CreateOrderLinkRequest;
import com.aura.order.link.dto.OrderLinkResponse;
import com.aura.order.order.CheckoutService;
import com.aura.order.order.FulfillmentStatus;
import com.aura.order.order.Order;
import com.aura.order.order.OrderItemRepository;
import com.aura.order.order.OrderRepository;
import com.aura.order.order.OrderSource;
import com.aura.order.order.PaymentStatus;
import com.aura.order.order.dto.OrderResponse;
import com.aura.order.payment.PaymentService;
import com.aura.order.payment.dto.PaymentStartResponse;
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

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A seller composing an order for someone on the telephone.
 *
 * <p>The interesting behaviour is all about the token: it is shown once, stored only as a hash,
 * spent when payment starts rather than when it succeeds, and indistinguishable from a wrong guess
 * once it is expired or used.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SellerOrderLinkServiceTest {

    private static final long SELLER = 7L;

    @Mock
    private SellerOrderLinkRepository linkRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderItemRepository orderItemRepository;

    @Mock
    private CheckoutService checkoutService;

    @Mock
    private PaymentService paymentService;

    private SellerOrderLinkService service;
    private Order order;
    private SellerOrderLink link;

    @BeforeEach
    void setUp() {
        service = new SellerOrderLinkService(linkRepository, orderRepository, orderItemRepository,
            checkoutService, paymentService,
            new OrderLinkProperties(Duration.ofHours(48), "http://shop/pay"));

        order = new Order();
        order.setId(42L);
        order.setTraceCode("ABCDEFGHJK");
        order.setTotal(1_040_000L);
        order.setPaymentStatus(PaymentStatus.PENDING);
        order.setBuyerFirstName("Ali");
        order.setBuyerLastName("Rezai");

        link = SellerOrderLink.of(42L, SELLER, "hash", OffsetDateTime.now().plusHours(48));
        link.setId(1L);

        when(orderRepository.findById(42L)).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderIdOrderByIdAsc(42L)).thenReturn(List.of());
        when(linkRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(checkoutService.placeForSeller(anyLong(), any(), any(), any()))
            .thenReturn(placedOrder());
        when(paymentService.start(anyString()))
            .thenReturn(new PaymentStartResponse("ABCDEFGHJK", "S1", "http://gw/S1", 1_040_000L));
    }

    private OrderResponse placedOrder() {
        return new OrderResponse(42L, "ABCDEFGHJK", PaymentStatus.PENDING,
            FulfillmentStatus.PENDING, OrderSource.SELLER_LINK, "Ali Rezai", "+989121234567",
            Map.of(), "1234567890", "Post", 40_000L, null, 0L, 1_000_000L, 1_040_000L,
            List.of(), null, null);
    }

    private CreateOrderLinkRequest request() {
        return new CreateOrderLinkRequest("Ali", "Rezai", "09121234567", "Tehran", "Tehran",
            "Somewhere 12", "1234567890", 3L,
            List.of(new CreateOrderLinkRequest.Line(11L, 2)));
    }

    /** Makes both lookups resolve to the link, whichever the code under test uses. */
    private void stored(SellerOrderLink stored) {
        when(linkRepository.findByTokenHash(anyString())).thenReturn(Optional.ofNullable(stored));
        when(linkRepository.lockByTokenHash(anyString())).thenReturn(Optional.ofNullable(stored));
    }

    @Nested
    @DisplayName("creating a link")
    class Creating {

        @Test
        @DisplayName("the order is written through the ordinary checkout path")
        void reusesCheckout() {
            // Not a second implementation. The ordering inside checkout is what stops an order
            // existing with no stock held for it, and a copy would get that wrong eventually.
            service.create(SELLER, request());

            verify(checkoutService).placeForSeller(org.mockito.ArgumentMatchers.eq(SELLER),
                any(), any(), any());
        }

        @Test
        @DisplayName("the seller is given the whole link, once")
        void returnsTheUrl() {
            OrderLinkResponse response = service.create(SELLER, request());

            assertThat(response.url()).startsWith("http://shop/pay/");
            assertThat(response.traceCode()).isEqualTo("ABCDEFGHJK");
            assertThat(response.total()).isEqualTo(1_040_000L);
        }

        @Test
        @DisplayName("only the hash is stored, never the token")
        void storesOnlyTheHash() {
            // A leaked database must not hand out working links.
            OrderLinkResponse response = service.create(SELLER, request());
            String rawToken = response.url().substring("http://shop/pay/".length());

            ArgumentCaptor<SellerOrderLink> saved = ArgumentCaptor.forClass(SellerOrderLink.class);
            verify(linkRepository).save(saved.capture());

            assertThat(saved.getValue().getTokenHash())
                .isEqualTo(LinkTokens.hash(rawToken))
                .isNotEqualTo(rawToken);
        }

        @Test
        @DisplayName("the link expires, because the stock behind it is held")
        void hasAnExpiry() {
            OrderLinkResponse response = service.create(SELLER, request());

            assertThat(response.expiresAt()).isAfter(OffsetDateTime.now().plusHours(47));
            assertThat(response.usedAt()).isNull();
        }

        @Test
        @DisplayName("a listed link no longer carries its URL")
        void listingOmitsTheToken() {
            // It cannot: the raw token was never stored. A seller who lost it composes a new order.
            when(linkRepository.findBySellerIdOrderByCreatedAtDesc(SELLER))
                .thenReturn(List.of(link));

            assertThat(service.listFor(SELLER).getFirst().url()).isNull();
        }
    }

    @Nested
    @DisplayName("opening a link")
    class Viewing {

        @Test
        @DisplayName("the buyer sees the order they are about to pay for")
        void showsTheOrder() {
            stored(link);

            assertThat(service.view("some-token").traceCode()).isEqualTo("ABCDEFGHJK");
        }

        @Test
        @DisplayName("an unknown token is a 404")
        void unknownToken() {
            stored(null);

            assertThatThrownBy(() -> service.view("nonsense"))
                .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("an expired link is indistinguishable from a wrong guess")
        void expiredLooksUnknown() {
            // Telling someone their token is "expired" tells them it was nearly right.
            link.setExpiresAt(OffsetDateTime.now().minusMinutes(1));
            stored(link);

            assertThatThrownBy(() -> service.view("some-token"))
                .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("a spent link is too")
        void usedLooksUnknown() {
            link.markUsed();
            stored(link);

            assertThatThrownBy(() -> service.view("some-token"))
                .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("looking does not spend it")
        void viewingIsNotUsing() {
            // A buyer who reads the page and comes back an hour later to pay is ordinary. A link
            // that dies on being opened is not a link.
            stored(link);

            service.view("some-token");

            assertThat(link.isUsed()).isFalse();
            verify(linkRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("paying through a link")
    class Paying {

        @Test
        @DisplayName("payment starts and the link is spent")
        void paysAndSpends() {
            stored(link);

            PaymentStartResponse payment = service.pay("some-token");

            assertThat(payment.redirectUrl()).isEqualTo("http://gw/S1");
            assertThat(link.isUsed()).isTrue();
        }

        @Test
        @DisplayName("the link is taken under a lock, not read plainly")
        void locksTheLink() {
            // Two taps on the same link a moment apart both find it unused otherwise, and two
            // payments are started for one order.
            stored(link);

            service.pay("some-token");

            verify(linkRepository).lockByTokenHash(anyString());
            verify(linkRepository, never()).findByTokenHash(anyString());
        }

        @Test
        @DisplayName("it is spent when payment begins, not when it succeeds")
        void spentOnStart() {
            // Leaving it open until settlement lets a second person start a second payment for the
            // same order while the first is still at their bank.
            stored(link);

            service.pay("some-token");

            assertThat(link.getUsedAt()).isNotNull();
            assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
        }

        @Test
        @DisplayName("a link cannot be used twice")
        void singleUse() {
            link.markUsed();
            stored(link);

            assertThatThrownBy(() -> service.pay("some-token"))
                .isInstanceOf(ResourceNotFoundException.class);
            verify(paymentService, never()).start(anyString());
        }

        @Test
        @DisplayName("an order already paid for refuses a second payment")
        void alreadyPaidOrder() {
            order.setPaymentStatus(PaymentStatus.PAID);
            stored(link);

            assertThatThrownBy(() -> service.pay("some-token"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("already been paid");
            verify(paymentService, never()).start(anyString());
        }

        @Test
        @DisplayName("an order whose stock has lapsed cannot be paid for")
        void expiredOrder() {
            order.setPaymentStatus(PaymentStatus.EXPIRED);
            stored(link);

            assertThatThrownBy(() -> service.pay("some-token"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("no longer be paid");
        }
    }
}
