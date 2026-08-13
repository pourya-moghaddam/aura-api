package com.aura.order.order;

import com.aura.common.events.OrderItemStatusChangedEvent;
import com.aura.common.events.Topics;
import com.aura.common.web.error.BusinessRuleException;
import com.aura.common.web.error.ResourceNotFoundException;
import com.aura.order.order.dto.SellerOrderResponse;
import com.aura.order.outbox.OutboxWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A seller's orders, and only theirs.
 *
 * <p>Two things are being tested and they are not the same. One is the workflow — which moves are
 * legal, what the order's status becomes, what the buyer is told. The other is the boundary: a
 * seller must not read or write another seller's line, and must not learn that it exists.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SellerOrderServiceTest {

    private static final long MINE = 7L;
    private static final long THEIRS = 8L;
    private static final long ORDER = 42L;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderItemRepository orderItemRepository;

    @Mock
    private OutboxWriter outboxWriter;

    @InjectMocks
    private SellerOrderService sellerOrderService;

    private Order order;
    private OrderItem myItem;
    private OrderItem theirItem;

    @BeforeEach
    void setUp() {
        order = new Order();
        order.setId(ORDER);
        order.setTraceCode("ABCDEFGHJK");
        order.setPaymentStatus(PaymentStatus.PAID);
        order.setBuyerFirstName("Ali");
        order.setBuyerLastName("Rezai");
        order.setBuyerPhone("+989121234567");
        order.setAddressSnapshot(Map.of("city", "Tehran"));
        order.setPostalCode("1234567890");
        order.setDeliveryName("Post");

        myItem = OrderItem.of(ORDER, 1L, 11L, MINE, "My Shirt", Map.of(), 500_000L, 2);
        myItem.setId(100L);
        theirItem = OrderItem.of(ORDER, 2L, 22L, THEIRS, "Their Hat", Map.of(), 300_000L, 1);
        theirItem.setId(200L);

        when(orderRepository.findById(ORDER)).thenReturn(Optional.of(order));
        when(orderRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(orderItemRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(orderItemRepository.existsBySellerIdAndOrderId(MINE, ORDER)).thenReturn(true);
        when(orderItemRepository.existsBySellerIdAndOrderId(THEIRS, ORDER)).thenReturn(true);
        when(orderItemRepository.findBySellerIdAndOrderId(MINE, ORDER)).thenReturn(List.of(myItem));
        when(orderItemRepository.findBySellerIdAndOrderId(THEIRS, ORDER)).thenReturn(List.of(theirItem));
        when(orderItemRepository.findByOrderIdOrderByIdAsc(ORDER))
            .thenReturn(List.of(myItem, theirItem));
        when(orderItemRepository.findById(100L)).thenReturn(Optional.of(myItem));
        when(orderItemRepository.findById(200L)).thenReturn(Optional.of(theirItem));
    }

    @Nested
    @DisplayName("what a seller can see")
    class Visibility {

        @Test
        @DisplayName("only their own lines, and only their own total")
        void showsOwnLinesOnly() {
            // The order is worth 1,300,000 between two sellers. Showing either of them the whole
            // figure tells them what the buyer spent elsewhere.
            SellerOrderResponse response = sellerOrderService.get(MINE, ORDER);

            assertThat(response.items()).hasSize(1);
            assertThat(response.items().getFirst().productName()).isEqualTo("My Shirt");
            assertThat(response.sellerTotal()).isEqualTo(1_000_000L);
        }

        @Test
        @DisplayName("enough about the buyer to send the parcel")
        void showsDeliveryDetails() {
            SellerOrderResponse response = sellerOrderService.get(MINE, ORDER);

            assertThat(response.buyerName()).isEqualTo("Ali Rezai");
            assertThat(response.buyerPhone()).isEqualTo("+989121234567");
            assertThat(response.postalCode()).isEqualTo("1234567890");
            assertThat(response.traceCode()).isEqualTo("ABCDEFGHJK");
        }

        @Test
        @DisplayName("an order they have nothing in is a 404, not a 403")
        void foreignOrderIsNotFound() {
            // "This exists but is not yours" tells a seller that an order with that id was placed,
            // which is not theirs to know.
            when(orderItemRepository.existsBySellerIdAndOrderId(99L, ORDER)).thenReturn(false);

            assertThatThrownBy(() -> sellerOrderService.get(99L, ORDER))
                .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("an unpaid order is invisible")
        void unpaidOrdersAreHidden() {
            // A shopper who may still be at their bank. Putting it on a seller's screen has them
            // packing parcels for money that never arrives.
            order.setPaymentStatus(PaymentStatus.PENDING);

            assertThatThrownBy(() -> sellerOrderService.get(MINE, ORDER))
                .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("each line says what it may become next")
        void linesCarryTheirNextSteps() {
            // So the screen renders exactly the buttons that will work rather than offering ones
            // the server will refuse.
            assertThat(sellerOrderService.get(MINE, ORDER).items().getFirst().allowedNext())
                .containsExactlyInAnyOrder(FulfillmentStatus.PROCESSING, FulfillmentStatus.CANCELLED);
        }
    }

    @Nested
    @DisplayName("advancing a line")
    class Advancing {

        @Test
        @DisplayName("a legal move is made and the buyer is told")
        void movesAndNotifies() {
            sellerOrderService.advance(MINE, ORDER, 100L, FulfillmentStatus.PROCESSING);

            assertThat(myItem.getFulfillmentStatus()).isEqualTo(FulfillmentStatus.PROCESSING);

            ArgumentCaptor<OrderItemStatusChangedEvent> event =
                ArgumentCaptor.forClass(OrderItemStatusChangedEvent.class);
            verify(outboxWriter).write(org.mockito.ArgumentMatchers.eq(Topics.ORDER_ITEM_STATUS_CHANGED),
                anyString(), event.capture());

            assertThat(event.getValue().previousStatus()).isEqualTo("PENDING");
            assertThat(event.getValue().newStatus()).isEqualTo("PROCESSING");
            assertThat(event.getValue().traceCode()).isEqualTo("ABCDEFGHJK");
            assertThat(event.getValue().buyerPhone()).isEqualTo("+989121234567");
        }

        @Test
        @DisplayName("the event is keyed by order, so a buyer's messages stay in order")
        void eventIsKeyedByOrder() {
            // "Delivered" arriving before "shipped" is a confusing message to receive, and that is
            // what happens when one order's events land on different partitions.
            sellerOrderService.advance(MINE, ORDER, 100L, FulfillmentStatus.PROCESSING);

            verify(outboxWriter).write(anyString(),
                org.mockito.ArgumentMatchers.eq(String.valueOf(ORDER)), any());
        }

        @Test
        @DisplayName("an illegal move is refused and nothing is written")
        void illegalMoveIsRefused() {
            assertThatThrownBy(() ->
                sellerOrderService.advance(MINE, ORDER, 100L, FulfillmentStatus.DELIVERED))
                .isInstanceOf(BusinessRuleException.class);

            assertThat(myItem.getFulfillmentStatus()).isEqualTo(FulfillmentStatus.PENDING);
            verify(outboxWriter, never()).write(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("a repeated move is harmless and notifies nobody twice")
        void repeatIsIdempotent() {
            // A double-clicked button. Erroring would have the seller wondering whether the first
            // click worked; notifying twice would send the buyer two identical messages.
            myItem.setFulfillmentStatus(FulfillmentStatus.PROCESSING);

            sellerOrderService.advance(MINE, ORDER, 100L, FulfillmentStatus.PROCESSING);

            verify(outboxWriter, never()).write(anyString(), anyString(), any());
            verify(orderItemRepository, never()).save(any());
        }

        @Test
        @DisplayName("a seller cannot move another seller's line")
        void cannotTouchAnotherSellersLine() {
            // Both sellers are on this order, so the seller passes the visibility check and is
            // stopped by the ownership check on the line itself.
            assertThatThrownBy(() ->
                sellerOrderService.advance(MINE, ORDER, 200L, FulfillmentStatus.PROCESSING))
                .isInstanceOf(ResourceNotFoundException.class);

            assertThat(theirItem.getFulfillmentStatus()).isEqualTo(FulfillmentStatus.PENDING);
        }

        @Test
        @DisplayName("a line id from another order is refused")
        void cannotMoveALineFromAnotherOrder() {
            OrderItem elsewhere = OrderItem.of(999L, 1L, 11L, MINE, "X", Map.of(), 1L, 1);
            elsewhere.setId(300L);
            when(orderItemRepository.findById(300L)).thenReturn(Optional.of(elsewhere));

            assertThatThrownBy(() ->
                sellerOrderService.advance(MINE, ORDER, 300L, FulfillmentStatus.PROCESSING))
                .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("the order's own status")
    class DerivedStatus {

        @Test
        @DisplayName("one seller shipping does not ship the order")
        void oneSellerIsNotEveryone() {
            // The order is only as far along as its least-advanced line, and the other seller has
            // not started. Telling the buyer their order has shipped would be a lie.
            myItem.setFulfillmentStatus(FulfillmentStatus.PROCESSING);

            sellerOrderService.advance(MINE, ORDER, 100L, FulfillmentStatus.SHIPPED);

            assertThat(order.getDerivedStatus()).isEqualTo(FulfillmentStatus.PENDING);
        }

        @Test
        @DisplayName("the order advances once every seller has")
        void orderFollowsTheSlowestSeller() {
            myItem.setFulfillmentStatus(FulfillmentStatus.PROCESSING);
            theirItem.setFulfillmentStatus(FulfillmentStatus.SHIPPED);

            sellerOrderService.advance(MINE, ORDER, 100L, FulfillmentStatus.SHIPPED);

            assertThat(order.getDerivedStatus()).isEqualTo(FulfillmentStatus.SHIPPED);
        }

        @Test
        @DisplayName("one seller cancelling does not cancel the order")
        void oneCancellationIsNotTheWholeOrder() {
            theirItem.setFulfillmentStatus(FulfillmentStatus.PROCESSING);

            sellerOrderService.advance(MINE, ORDER, 100L, FulfillmentStatus.CANCELLED);

            assertThat(order.getDerivedStatus()).isEqualTo(FulfillmentStatus.PROCESSING);
        }

        @Test
        @DisplayName("the event carries the order's status, not just the line's")
        void eventCarriesBothStatuses() {
            // So a consumer can say "your order is on its way" only when every seller has sent,
            // while still reporting this particular dispatch.
            myItem.setFulfillmentStatus(FulfillmentStatus.PROCESSING);

            sellerOrderService.advance(MINE, ORDER, 100L, FulfillmentStatus.SHIPPED);

            ArgumentCaptor<OrderItemStatusChangedEvent> event =
                ArgumentCaptor.forClass(OrderItemStatusChangedEvent.class);
            verify(outboxWriter).write(anyString(), anyString(), event.capture());

            assertThat(event.getValue().newStatus()).isEqualTo("SHIPPED");
            assertThat(event.getValue().orderStatus()).isEqualTo("PENDING");
        }
    }
}
