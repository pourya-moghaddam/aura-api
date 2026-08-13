package com.aura.order.cart;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * One line of a cart.
 */
@Entity
@Table(name = "cart_items")
@Getter
@Setter
@NoArgsConstructor
public class CartItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cart_id", nullable = false)
    private Long cartId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "variant_id", nullable = false)
    private Long variantId;

    @Column(nullable = false)
    private int quantity;

    /**
     * What it cost when it went in. Never charged — checkout re-reads every price from catalog —
     * and kept only so the cart can say "this got more expensive since you added it". Treating it
     * as the price to charge is how a shopper pays last month's price for today's stock.
     */
    @Column(name = "price_at_add", nullable = false)
    private Long priceAtAdd;

    @Column(name = "added_at", updatable = false)
    private OffsetDateTime addedAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public static CartItem of(Long cartId, Long productId, Long variantId, int quantity, Long priceAtAdd) {
        CartItem item = new CartItem();
        item.cartId = cartId;
        item.productId = productId;
        item.variantId = variantId;
        item.quantity = quantity;
        item.priceAtAdd = priceAtAdd;
        item.addedAt = OffsetDateTime.now();
        item.updatedAt = item.addedAt;
        return item;
    }

    public void touch() {
        this.updatedAt = OffsetDateTime.now();
    }
}
