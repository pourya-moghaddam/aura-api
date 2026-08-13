package com.aura.order.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One line of an order: what was bought, from whom, at what price, and how far along it is.
 *
 * <p>The product may later be renamed, repriced or archived entirely; the snapshots here are what
 * make the order still render — and still tell the truth — a year afterwards.
 */
@Entity
@Table(name = "order_items")
@Getter
@Setter
@NoArgsConstructor
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "variant_id", nullable = false)
    private Long variantId;

    /**
     * Which seller owes this line. No financial meaning — settlement happens out of band — it
     * exists so a seller sees and advances their own items and nobody else's.
     */
    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Column(name = "product_name_snapshot", nullable = false, length = 255)
    private String productNameSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "variant_snapshot", nullable = false, columnDefinition = "jsonb")
    private Map<String, String> variantSnapshot = new LinkedHashMap<>();

    @Column(name = "unit_price", nullable = false)
    private Long unitPrice;

    @Column(nullable = false)
    private Integer quantity;

    /**
     * Stored rather than computed on read. It is what the customer was charged for this line;
     * deriving it later from a price that has since moved would quietly rewrite the invoice.
     */
    @Column(name = "line_total", nullable = false)
    private Long lineTotal;

    @Enumerated(EnumType.STRING)
    @Column(name = "fulfillment_status", nullable = false, length = 20)
    private FulfillmentStatus fulfillmentStatus = FulfillmentStatus.PENDING;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public static OrderItem of(Long orderId, Long productId, Long variantId, Long sellerId,
                               String productName, Map<String, String> variantSnapshot,
                               long unitPrice, int quantity) {
        OrderItem item = new OrderItem();
        item.orderId = orderId;
        item.productId = productId;
        item.variantId = variantId;
        item.sellerId = sellerId;
        item.productNameSnapshot = productName;
        item.variantSnapshot = variantSnapshot;
        item.unitPrice = unitPrice;
        item.quantity = quantity;
        item.lineTotal = unitPrice * quantity;
        item.createdAt = OffsetDateTime.now();
        item.updatedAt = item.createdAt;
        return item;
    }

    public void touch() {
        this.updatedAt = OffsetDateTime.now();
    }
}
