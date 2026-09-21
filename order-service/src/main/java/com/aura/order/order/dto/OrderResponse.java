package com.aura.order.order.dto;

import com.aura.order.order.FulfillmentStatus;
import com.aura.order.order.Order;
import com.aura.order.order.OrderItem;
import com.aura.order.order.OrderSource;
import com.aura.order.order.PaymentStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * An order as its buyer sees it.
 *
 * @param traceCode what the customer quotes; the id is deliberately not the same thing
 * @param total     subtotal − discount + delivery, in Rial
 */
public record OrderResponse(
    Long id,
    String traceCode,
    PaymentStatus paymentStatus,
    FulfillmentStatus status,
    OrderSource source,
    String buyerName,
    String buyerPhone,
    Map<String, String> address,
    String postalCode,
    String deliveryName,
    Long deliveryFee,
    String discountCode,
    Long discountAmount,
    Long subtotal,
    Long total,
    List<OrderItemResponse> items,
    OffsetDateTime createdAt,
    OffsetDateTime paidAt
) {

    public static OrderResponse of(Order order, List<OrderItem> items) {
        return new OrderResponse(
            order.getId(), order.getTraceCode(), order.getPaymentStatus(),
            order.getDerivedStatus(), order.getSource(),
            order.buyerName(), order.getBuyerPhone(),
            order.getAddressSnapshot(), order.getPostalCode(),
            order.getDeliveryName(), order.getDeliveryFee(),
            order.getDiscountCode(), order.getDiscountAmount(),
            order.getSubtotal(), order.getTotal(),
            items.stream().map(OrderItemResponse::from).toList(),
            order.getCreatedAt(), order.getPaidAt());
    }

    /**
     * One line. The seller id is deliberately absent — a buyer has no use for it, and requirement
     * 8's seller views are a separate surface.
     */
    public record OrderItemResponse(
        Long id,
        Long productId,
        Long variantId,
        String productName,
        Map<String, String> variant,
        Long unitPrice,
        Integer quantity,
        Long lineTotal,
        FulfillmentStatus fulfillmentStatus
    ) {

        static OrderItemResponse from(OrderItem item) {
            return new OrderItemResponse(item.getId(), item.getProductId(), item.getVariantId(),
                item.getProductNameSnapshot(), item.getVariantSnapshot(), item.getUnitPrice(),
                item.getQuantity(), item.getLineTotal(), item.getFulfillmentStatus());
        }
    }
}
