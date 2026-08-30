package com.aura.order.order;

import com.aura.common.web.error.ResourceNotFoundException;
import com.aura.order.order.dto.AdminOrderResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The administrative view of every order.
 *
 * <p>What is worth testing here is not that a list comes back, but the three things that make this
 * different from the seller's list: it is not filtered by ownership, it does not hide unpaid
 * orders, and it names the seller behind each line.
 */
@ExtendWith(MockitoExtension.class)
class AdminOrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderItemRepository orderItemRepository;

    @InjectMocks
    private AdminOrderService adminOrderService;

    private final Pageable pageable = PageRequest.of(0, 20);

    @Test
    @DisplayName("lists every order, unpaid ones included")
    void includesUnpaidOrders() {
        Order paid = order(1L, "AAAAAA", PaymentStatus.PAID);
        Order unpaid = order(2L, "BBBBBB", PaymentStatus.PENDING);
        given(orderRepository.findAllByOrderByCreatedAtDesc(pageable))
            .willReturn(new PageImpl<>(List.of(paid, unpaid)));
        given(orderItemRepository.findByOrderIdInOrderByIdAsc(anyCollection()))
            .willReturn(List.of(item(10L, 1L, 7L), item(11L, 2L, 8L)));

        Page<AdminOrderResponse> result = adminOrderService.list(null, pageable);

        // The seller's own query is PAID-only by design. This one must not be, or the orders most
        // worth an administrator's attention are the ones they cannot see.
        assertThat(result.getContent())
            .extracting(AdminOrderResponse::paymentStatus)
            .containsExactly(PaymentStatus.PAID, PaymentStatus.PENDING);
    }

    @Test
    @DisplayName("names the seller behind every line")
    void carriesSellerId() {
        given(orderRepository.findAllByOrderByCreatedAtDesc(pageable))
            .willReturn(new PageImpl<>(List.of(order(1L, "AAAAAA", PaymentStatus.PAID))));
        given(orderItemRepository.findByOrderIdInOrderByIdAsc(anyCollection()))
            .willReturn(List.of(item(10L, 1L, 7L), item(11L, 1L, 9L)));

        Page<AdminOrderResponse> result = adminOrderService.list(null, pageable);

        // The whole reason this DTO exists rather than reusing the buyer's, which omits it.
        assertThat(result.getContent().getFirst().items())
            .extracting(AdminOrderResponse.AdminOrderItem::sellerId)
            .containsExactly(7L, 9L);
    }

    @Test
    @DisplayName("groups each order's own lines, and never another's")
    void groupsItemsByOrder() {
        given(orderRepository.findAllByOrderByCreatedAtDesc(pageable))
            .willReturn(new PageImpl<>(List.of(
                order(1L, "AAAAAA", PaymentStatus.PAID),
                order(2L, "BBBBBB", PaymentStatus.PAID))));
        given(orderItemRepository.findByOrderIdInOrderByIdAsc(anyCollection()))
            .willReturn(List.of(item(10L, 1L, 7L), item(11L, 2L, 7L), item(12L, 1L, 8L)));

        List<AdminOrderResponse> result = adminOrderService.list(null, pageable).getContent();

        // One flat query grouped in memory: the grouping is where that optimisation could go wrong,
        // by hanging one order's lines off another.
        assertThat(result.getFirst().items()).extracting(AdminOrderResponse.AdminOrderItem::id)
            .containsExactly(10L, 12L);
        assertThat(result.get(1).items()).extracting(AdminOrderResponse.AdminOrderItem::id)
            .containsExactly(11L);
    }

    @Test
    @DisplayName("an order with no lines still renders, rather than disappearing")
    void toleratesAnOrderWithNoItems() {
        given(orderRepository.findAllByOrderByCreatedAtDesc(pageable))
            .willReturn(new PageImpl<>(List.of(order(1L, "AAAAAA", PaymentStatus.PENDING))));
        given(orderItemRepository.findByOrderIdInOrderByIdAsc(anyCollection()))
            .willReturn(List.of());

        List<AdminOrderResponse> result = adminOrderService.list(null, pageable).getContent();

        // getOrDefault rather than get: a null here would be a NullPointerException on the one
        // screen someone opens when something has already gone wrong.
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().items()).isEmpty();
    }

    @Test
    @DisplayName("an empty page asks for no items at all")
    void emptyPageSkipsTheItemQuery() {
        given(orderRepository.findAllByOrderByCreatedAtDesc(pageable))
            .willReturn(new PageImpl<>(List.of()));

        assertThat(adminOrderService.list(null, pageable)).isEmpty();
        // `findByOrderIdIn` with an empty collection is a query that can only return nothing.
        verify(orderItemRepository, never()).findByOrderIdInOrderByIdAsc(anyCollection());
    }

    @Test
    @DisplayName("filters by payment status when asked")
    void filtersByPaymentStatus() {
        given(orderRepository.findByPaymentStatusOrderByCreatedAtDesc(PaymentStatus.PENDING, pageable))
            .willReturn(new PageImpl<>(List.of(order(2L, "BBBBBB", PaymentStatus.PENDING))));
        given(orderItemRepository.findByOrderIdInOrderByIdAsc(anyCollection()))
            .willReturn(List.of());

        assertThat(adminOrderService.list(PaymentStatus.PENDING, pageable).getContent())
            .extracting(AdminOrderResponse::traceCode)
            .containsExactly("BBBBBB");

        verify(orderRepository, never()).findAllByOrderByCreatedAtDesc(pageable);
    }

    @Test
    @DisplayName("an unknown id is not found, rather than empty")
    void unknownOrderIsNotFound() {
        given(orderRepository.findById(404L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> adminOrderService.get(404L))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    private Order order(long id, String traceCode, PaymentStatus paymentStatus) {
        Order order = new Order();
        ReflectionTestUtils.setField(order, "id", id);
        ReflectionTestUtils.setField(order, "traceCode", traceCode);
        ReflectionTestUtils.setField(order, "paymentStatus", paymentStatus);
        return order;
    }

    private OrderItem item(long id, long orderId, long sellerId) {
        OrderItem item = new OrderItem();
        ReflectionTestUtils.setField(item, "id", id);
        ReflectionTestUtils.setField(item, "orderId", orderId);
        ReflectionTestUtils.setField(item, "sellerId", sellerId);
        ReflectionTestUtils.setField(item, "fulfillmentStatus", FulfillmentStatus.PENDING);
        return item;
    }
}
