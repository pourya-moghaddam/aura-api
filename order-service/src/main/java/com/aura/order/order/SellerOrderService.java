package com.aura.order.order;

import com.aura.common.events.OrderItemStatusChangedEvent;
import com.aura.common.events.Topics;
import com.aura.common.web.error.BusinessRuleException;
import com.aura.common.web.error.ResourceNotFoundException;
import com.aura.order.order.dto.SellerOrderResponse;
import com.aura.order.outbox.OutboxWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A seller's orders, and only theirs — requirement 8.
 *
 * <p>The filtering here is the security boundary, not a convenience. One basket can hold several
 * sellers' products, so every read is scoped to the seller's own lines and every write checks that
 * the line being moved belongs to them. A seller must not learn what else the buyer bought, from
 * whom, or for how much.
 *
 * <p>The order-level status is derived from its items after each change and never set directly:
 * the order is only as far along as its least-advanced line.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SellerOrderService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OutboxWriter outboxWriter;

    /**
     * Orders containing at least one of this seller's lines, newest first.
     *
     * <p>Only paid ones. An unpaid order is a shopper who may still be at their bank, and showing
     * it as work to do would have sellers packing parcels for money that never arrives.
     */
    @Transactional(readOnly = true)
    public Page<SellerOrderResponse> list(long sellerId, Pageable pageable) {
        Page<Order> orders = orderRepository.findPaidOrdersForSeller(sellerId, pageable);

        Map<Long, List<OrderItem>> itemsByOrder = orderItemRepository
            .findBySellerIdAndOrderIdIn(sellerId, orders.map(Order::getId).toList())
            .stream()
            .collect(Collectors.groupingBy(OrderItem::getOrderId));

        return orders.map(order -> SellerOrderResponse.of(
            order, itemsByOrder.getOrDefault(order.getId(), List.of())));
    }

    /** One order, showing only this seller's lines. */
    @Transactional(readOnly = true)
    public SellerOrderResponse get(long sellerId, long orderId) {
        Order order = requireVisible(sellerId, orderId);
        return SellerOrderResponse.of(order,
            orderItemRepository.findBySellerIdAndOrderId(sellerId, orderId));
    }

    /**
     * Moves one of the seller's lines along.
     *
     * <p>Refuses anything that is not a legal next step, so a line cannot be walked backwards
     * after the buyer has been told about it, and refuses lines belonging to another seller — with
     * a 404 rather than a 403, because confirming the line exists would tell one seller something
     * about another's business.
     */
    @Transactional
    public SellerOrderResponse advance(long sellerId, long orderId, long itemId,
                                       FulfillmentStatus next) {
        Order order = requireVisible(sellerId, orderId);

        OrderItem item = orderItemRepository.findById(itemId)
            .filter(candidate -> candidate.getOrderId().equals(orderId))
            .filter(candidate -> candidate.getSellerId() == sellerId)
            .orElseThrow(() -> ResourceNotFoundException.of("Order item", itemId));

        FulfillmentStatus previous = item.getFulfillmentStatus();
        if (previous == next) {
            // Idempotent: a double-clicked button is not an error, and telling the seller off for
            // it would have them wondering whether the first click worked.
            log.debug("Order item {} is already {}", itemId, next);
            return get(sellerId, orderId);
        }

        if (!previous.canMoveTo(next)) {
            throw new BusinessRuleException("invalid-status-transition",
                previous.isTerminal()
                    ? "This item is already " + previous.name().toLowerCase() + "."
                    : "An item that is " + previous.name().toLowerCase()
                        + " cannot be marked " + next.name().toLowerCase() + ".");
        }

        item.setFulfillmentStatus(next);
        item.touch();
        orderItemRepository.save(item);

        // Every line, not just this seller's: the order's status is derived from all of them.
        List<OrderItem> allItems = orderItemRepository.findByOrderIdOrderByIdAsc(orderId);
        order.deriveStatusFrom(allItems);
        order.touch();
        orderRepository.save(order);

        // Written in this transaction, so the buyer is never told about a change that did not
        // commit - and never left uninformed about one that did.
        outboxWriter.write(Topics.ORDER_ITEM_STATUS_CHANGED, String.valueOf(orderId),
            eventFor(order, item, previous));

        log.info("Seller {} moved item {} of order {} from {} to {}; the order is now {}",
            sellerId, itemId, order.getTraceCode(), previous, next, order.getDerivedStatus());

        return SellerOrderResponse.of(order,
            allItems.stream().filter(candidate -> candidate.getSellerId() == sellerId).toList());
    }

    private Order requireVisible(long sellerId, long orderId) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> ResourceNotFoundException.of("Order", orderId));

        if (!orderItemRepository.existsBySellerIdAndOrderId(sellerId, orderId)) {
            // A 404 rather than a 403. "This order exists but is not yours" tells a seller that
            // an order with that id was placed, which is not theirs to know.
            throw ResourceNotFoundException.of("Order", orderId);
        }
        if (order.getPaymentStatus() != PaymentStatus.PAID) {
            throw ResourceNotFoundException.of("Order", orderId);
        }
        return order;
    }

    private OrderItemStatusChangedEvent eventFor(Order order, OrderItem item,
                                                 FulfillmentStatus previous) {
        return new OrderItemStatusChangedEvent(
            UUID.randomUUID(), Instant.now(),
            order.getId(), item.getId(), order.getTraceCode(),
            item.getSellerId(), item.getProductId(), item.getVariantId(),
            item.getProductNameSnapshot(), item.getQuantity(),
            previous.name(), item.getFulfillmentStatus().name(),
            order.getDerivedStatus().name(),
            order.getBuyerPhone(), order.buyerName(), order.getUserId());
    }
}
