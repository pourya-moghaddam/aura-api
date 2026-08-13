package com.aura.order.cart;

import com.aura.common.security.CurrentUser;
import com.aura.order.cart.dto.AddCartItemRequest;
import com.aura.order.cart.dto.CartResponse;
import com.aura.order.cart.dto.UpdateCartItemRequest;
import com.aura.order.config.CartProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;
import java.util.UUID;

/**
 * The cart, for shoppers with and without accounts.
 *
 * <p>Every endpoint works anonymously. Identity is taken from the bearer token when one is
 * present and from the cart cookie otherwise — never demanded — because requirement 12 makes
 * guests first-class buyers.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/orders/cart")
public class CartController {

    private final CartService cartService;
    private final CartOwnerResolver cartOwnerResolver;
    private final CartProperties cartProperties;

    @GetMapping
    public ResponseEntity<CartResponse> view(HttpServletRequest request, HttpServletResponse response) {
        // A plain view never mints a token: a visitor who only looks should not collect a cookie,
        // and a cart row for every such visitor is a table full of empty carts.
        return cartOwnerResolver.resolveExisting(request)
            .map(owner -> ResponseEntity.ok(cartService.view(owner)))
            .orElseGet(() -> ResponseEntity.ok(CartResponse.empty()));
    }

    @PostMapping("/items")
    public ResponseEntity<CartResponse> addItem(
        @Valid @RequestBody AddCartItemRequest request,
        HttpServletRequest httpRequest,
        HttpServletResponse httpResponse
    ) {
        CartOwner owner = cartOwnerResolver.resolveOrIssue(httpRequest, httpResponse);
        return ResponseEntity.ok(
            cartService.addItem(owner, request.variantId(), request.quantity()));
    }

    @PutMapping("/items/{variantId}")
    public ResponseEntity<CartResponse> setQuantity(
        @PathVariable long variantId,
        @Valid @RequestBody UpdateCartItemRequest request,
        HttpServletRequest httpRequest,
        HttpServletResponse httpResponse
    ) {
        CartOwner owner = cartOwnerResolver.resolveOrIssue(httpRequest, httpResponse);
        return ResponseEntity.ok(cartService.setQuantity(owner, variantId, request.quantity()));
    }

    @DeleteMapping("/items/{variantId}")
    public ResponseEntity<CartResponse> removeItem(
        @PathVariable long variantId,
        HttpServletRequest httpRequest,
        HttpServletResponse httpResponse
    ) {
        CartOwner owner = cartOwnerResolver.resolveOrIssue(httpRequest, httpResponse);
        return ResponseEntity.ok(cartService.removeItem(owner, variantId));
    }

    @DeleteMapping
    public ResponseEntity<CartResponse> clear(
        HttpServletRequest httpRequest,
        HttpServletResponse httpResponse
    ) {
        CartOwner owner = cartOwnerResolver.resolveOrIssue(httpRequest, httpResponse);
        return ResponseEntity.ok(cartService.clear(owner));
    }

    /**
     * Folds the guest cart into the account's, called by the client immediately after signing in.
     *
     * <p>Client-driven rather than something auth-service triggers, because the guest cookie is
     * the only link between the two carts and only the browser holds it. Auth-service has no way
     * to know which basket, if any, belongs to the person who just signed in.
     *
     * <p>Requires a token: there is nothing to merge into without an account.
     */
    @PostMapping("/merge")
    public ResponseEntity<CartResponse> merge(
        HttpServletRequest httpRequest,
        HttpServletResponse httpResponse
    ) {
        long userId = CurrentUser.requiredId();

        Optional<UUID> guestToken = CartTokenCookie.read(httpRequest);
        if (guestToken.isEmpty()) {
            // Nothing to fold in - the shopper had no guest basket. Their own cart is the answer.
            return ResponseEntity.ok(cartService.view(CartOwner.user(userId)));
        }

        CartResponse merged = cartService.mergeGuestCartInto(userId, guestToken.get());

        // The guest cart is gone; clearing the cookie stops a stale token being presented later
        // for a basket that now belongs to an account.
        CartTokenCookie.clear(httpResponse, cartProperties.cookieSecure());

        return ResponseEntity.ok(merged);
    }

}
