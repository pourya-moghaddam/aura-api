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
 * An order as whoever runs the shop sees it.
 *
 * <p>The third view of an order, and each exists because the others are wrong for the job.
 * {@link OrderResponse} is the buyer's: everything they paid for, nothing about who supplies it.
 * {@link SellerOrderResponse} is one seller's: their lines only, and none of the order's money.
 * This one is the whole order <em>including</em> which seller owns each line — the question only an
 * administrator asks, and the reason this could not simply reuse the buyer's DTO.
 *
 * <p>Unlike the seller's list, this includes <strong>unpaid</strong> orders. The seller query
 * filters them out deliberately, because an unpaid order is a shopper who may still be at their
 * bank and putting it on a packing screen invites parcels sent for money that never arrives. An
 * administrator has the opposite need: an order stuck unpaid is exactly the one worth looking at.
 */
public record AdminOrderResponse(
    Long id,
    String traceCode,
    PaymentStatus paymentStatus,
    FulfillmentStatus status,
    OrderSource source,
    Long userId,
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
    List<AdminOrderItem> items,
    OffsetDateTime createdAt,
    OffsetDateTime paidAt
) {

    public static AdminOrderResponse of(Order order, List<OrderItem> items) {
        return new AdminOrderResponse(
            order.getId(), order.getTraceCode(), order.getPaymentStatus(),
            order.getDerivedStatus(), order.getSource(), order.getUserId(),
            order.buyerName(), order.getBuyerPhone(),
            order.getAddressSnapshot(), order.getPostalCode(),
            order.getDeliveryName(), order.getDeliveryFee(),
            order.getDiscountCode(), order.getDiscountAmount(),
            order.getSubtotal(), order.getTotal(),
            items.stream().map(AdminOrderItem::from).toList(),
            order.getCreatedAt(), order.getPaidAt());
    }

    /**
     * One line, with the seller responsible for it.
     *
     * @param sellerId the whole point of this DTO. Without it an administrator looking at a stalled
     *                 order cannot tell whom to ask about it.
     */
    public record AdminOrderItem(
        Long id,
        Long sellerId,
        Long productId,
        Long variantId,
        String productName,
        Map<String, String> variant,
        Long unitPrice,
        Integer quantity,
        Long lineTotal,
        FulfillmentStatus fulfillmentStatus
    ) {

        static AdminOrderItem from(OrderItem item) {
            return new AdminOrderItem(item.getId(), item.getSellerId(), item.getProductId(),
                item.getVariantId(), item.getProductNameSnapshot(), item.getVariantSnapshot(),
                item.getUnitPrice(), item.getQuantity(), item.getLineTotal(),
                item.getFulfillmentStatus());
        }
    }
}
