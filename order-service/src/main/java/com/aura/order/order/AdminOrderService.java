package com.aura.order.order;

import com.aura.common.web.error.ResourceNotFoundException;
import com.aura.order.order.dto.AdminOrderResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Every order, for whoever runs the shop.
 *
 * <p>Separate from {@link SellerOrderService} rather than a widening of it. That service filters
 * every query by the seller in the token, and its own documentation argues against making one code
 * path sometimes filter by ownership and sometimes not — a conditional filter is the kind of thing
 * that is correct on the day it is written and leaks a year later when someone adds a branch.
 */
@Service
@RequiredArgsConstructor
public class AdminOrderService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;

    /**
     * Newest first, optionally narrowed to one payment status.
     *
     * <p>Items for the whole page are fetched in a single query and grouped in memory. Asking per
     * order would be twenty queries for a page of twenty, and the cost of that only becomes
     * obvious once there are enough orders for anyone to notice.
     */
    @Transactional(readOnly = true)
    public Page<AdminOrderResponse> list(PaymentStatus paymentStatus, Pageable pageable) {
        Page<Order> orders = paymentStatus == null
            ? orderRepository.findAllByOrderByCreatedAtDesc(pageable)
            : orderRepository.findByPaymentStatusOrderByCreatedAtDesc(paymentStatus, pageable);

        if (orders.isEmpty()) {
            return orders.map(order -> AdminOrderResponse.of(order, List.of()));
        }

        Map<Long, List<OrderItem>> itemsByOrder = orderItemRepository
            .findByOrderIdInOrderByIdAsc(orders.getContent().stream().map(Order::getId).toList())
            .stream()
            .collect(Collectors.groupingBy(OrderItem::getOrderId));

        return orders.map(order ->
            AdminOrderResponse.of(order, itemsByOrder.getOrDefault(order.getId(), List.of())));
    }

    @Transactional(readOnly = true)
    public AdminOrderResponse get(long orderId) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> ResourceNotFoundException.of("Order", orderId));

        return AdminOrderResponse.of(order, orderItemRepository.findByOrderIdOrderByIdAsc(orderId));
    }
}
