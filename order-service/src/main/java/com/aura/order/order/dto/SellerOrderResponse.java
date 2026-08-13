package com.aura.order.order.dto;

import com.aura.order.order.FulfillmentStatus;
import com.aura.order.order.Order;
import com.aura.order.order.OrderItem;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An order as one seller sees it.
 *
 * <p>Deliberately narrower than {@link OrderResponse}. It carries the buyer's name, telephone and
 * address — a seller has to be able to send the parcel — and their own lines with their own
 * totals. It does <em>not</em> carry the order's subtotal, discount, delivery fee or any other
 * seller's lines: what else the buyer bought, from whom, and what they paid overall is not this
 * seller's business.
 *
 * @param sellerTotal what this seller's lines came to, which is not the order total
 * @param orderStatus the whole order's derived status, so a seller can see they are waiting on
 *                    someone else
 */
public record SellerOrderResponse(
    Long orderId,
    String traceCode,
    FulfillmentStatus orderStatus,
    String buyerName,
    String buyerPhone,
    Map<String, String> address,
    String postalCode,
    String deliveryName,
    Long sellerTotal,
    List<SellerOrderItem> items,
    OffsetDateTime placedAt,
    OffsetDateTime paidAt
) {

    public static SellerOrderResponse of(Order order, List<OrderItem> sellerItems) {
        return new SellerOrderResponse(
            order.getId(), order.getTraceCode(), order.getDerivedStatus(),
            order.buyerName(), order.getBuyerPhone(),
            order.getAddressSnapshot(), order.getPostalCode(), order.getDeliveryName(),
            sellerItems.stream().mapToLong(OrderItem::getLineTotal).sum(),
            sellerItems.stream().map(SellerOrderItem::from).toList(),
            order.getCreatedAt(), order.getPaidAt());
    }

    /**
     * @param allowedNext what this line may be moved to next, so the screen can render exactly the
     *                    buttons that will work rather than offering ones the server will refuse
     */
    public record SellerOrderItem(
        Long id,
        Long productId,
        Long variantId,
        String productName,
        Map<String, String> variant,
        Long unitPrice,
        Integer quantity,
        Long lineTotal,
        FulfillmentStatus fulfillmentStatus,
        Set<FulfillmentStatus> allowedNext
    ) {

        static SellerOrderItem from(OrderItem item) {
            return new SellerOrderItem(item.getId(), item.getProductId(), item.getVariantId(),
                item.getProductNameSnapshot(), item.getVariantSnapshot(), item.getUnitPrice(),
                item.getQuantity(), item.getLineTotal(), item.getFulfillmentStatus(),
                item.getFulfillmentStatus().allowedNext());
        }
    }
}
