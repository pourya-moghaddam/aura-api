package com.aura.order.cart;

import com.aura.common.security.CurrentUser;
import com.aura.order.config.CartProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Works out whose cart a request is about.
 *
 * <p>Its own component rather than a helper on the controller because checkout, discounts and the
 * cart itself all need the same answer, and the rule that a signed-in account beats a leftover
 * guest cookie has to be identical everywhere. Two copies of it would eventually disagree, and the
 * way they disagree is that a shopper's basket appears to change depending on which page they are
 * looking at.
 */
@Component
@RequiredArgsConstructor
public class CartOwnerResolver {

    private final CartProperties cartProperties;

    /**
     * Who owns the cart for this request, without creating anything.
     *
     * <p>A signed-in shopper's account wins over any cookie they still carry: once they have an
     * account the cart follows the account, and a leftover guest token must not shadow it.
     */
    public Optional<CartOwner> resolveExisting(HttpServletRequest request) {
        return CurrentUser.id()
            .map(CartOwner::user)
            .or(() -> CartTokenCookie.read(request).map(CartOwner::guest));
    }

    /**
     * Same, but mints a guest token when there is no other identity — for the writes, where a cart
     * has to exist for the shopper to come back to.
     */
    public CartOwner resolveOrIssue(HttpServletRequest request, HttpServletResponse response) {
        return resolveExisting(request).orElseGet(() -> {
            UUID token = UUID.randomUUID();
            CartTokenCookie.set(response, token, cartProperties.cookieSecure());
            return CartOwner.guest(token);
        });
    }
}
