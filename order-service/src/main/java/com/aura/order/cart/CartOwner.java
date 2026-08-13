package com.aura.order.cart;

import java.util.UUID;

/**
 * Who a cart belongs to: a signed-in shopper, or a guest holding a token.
 *
 * <p>A single type rather than passing a nullable user id and a nullable token around. The rule
 * that exactly one is set is the same rule the {@code ck_cart_owner} constraint enforces in the
 * database, and having it in one place means no call site can quietly get it wrong.
 */
public record CartOwner(Long userId, UUID cartToken) {

    public CartOwner {
        boolean hasUser = userId != null;
        boolean hasToken = cartToken != null;
        if (hasUser == hasToken) {
            throw new IllegalArgumentException(
                "A cart belongs to exactly one of a user or a guest token");
        }
    }

    public static CartOwner user(long userId) {
        return new CartOwner(userId, null);
    }

    public static CartOwner guest(UUID cartToken) {
        return new CartOwner(null, cartToken);
    }

    public boolean isUser() {
        return userId != null;
    }
}
