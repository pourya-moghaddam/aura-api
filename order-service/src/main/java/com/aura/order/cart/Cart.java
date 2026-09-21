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
import java.util.UUID;

/**
 * A shopper's basket, whether or not they have an account.
 *
 * <p>Exactly one of {@code userId} and {@code cartToken} is set — a database CHECK enforces it.
 * That is what makes requirement 12 work: a guest's cart is identified by an unguessable token in
 * an httpOnly cookie, and needs no account behind it.
 */
@Entity
@Table(name = "carts")
@Getter
@Setter
@NoArgsConstructor
public class Cart {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "cart_token")
    private UUID cartToken;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public static Cart forUser(long userId) {
        Cart cart = new Cart();
        cart.userId = userId;
        cart.stamp();
        return cart;
    }

    public static Cart forGuest(UUID cartToken) {
        Cart cart = new Cart();
        cart.cartToken = cartToken;
        cart.stamp();
        return cart;
    }

    public boolean isGuest() {
        return userId == null;
    }

    /**
     * Bumped on every change. Guest carts are swept by age, so touching this is what keeps an
     * actively used cart from being reaped out from under the shopper.
     */
    public void touch() {
        this.updatedAt = OffsetDateTime.now();
    }

    private void stamp() {
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = this.createdAt;
    }
}
