package com.aura.order.order;

import com.aura.common.security.CurrentUser;
import com.aura.order.cart.CartOwner;
import com.aura.order.cart.CartOwnerResolver;
import com.aura.order.order.dto.CheckoutRequest;
import com.aura.order.order.dto.OrderResponse;
import com.aura.common.web.error.BusinessRuleException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Checkout and order lookup, both reachable without an account (requirement 12).
 */
@RestController
@RequiredArgsConstructor
public class CheckoutController {

    private final CheckoutService checkoutService;
    private final CartOwnerResolver cartOwnerResolver;

    /**
     * Places the order and hands back its trace code.
     *
     * <p>Deliberately does <em>not</em> mint a cart token when there is none: a checkout with no
     * basket is an error, not the start of one, and issuing a cookie here would leave a token
     * behind for every stray POST.
     */
    @PostMapping("/api/orders/checkout")
    public ResponseEntity<OrderResponse> checkout(
        @Valid @RequestBody CheckoutRequest request,
        HttpServletRequest httpRequest
    ) {
        CartOwner owner = cartOwnerResolver.resolveExisting(httpRequest)
            .orElseThrow(() -> new BusinessRuleException("cart-empty", "Your basket is empty."));

        OrderResponse order = checkoutService.checkout(owner, request);

        // The cart cookie is deliberately left alone. Clearing it looks tidy - the basket is gone
        // with the order - but it breaks the retry it is most important to survive: a browser
        // resending a timed-out checkout arrives with no cookie, is refused here as "your basket
        // is empty", and never reaches the idempotency key that would have returned the original
        // order. Found by exercising the retry against the running stack; the service-level test
        // could not see it, because it is handed an owner rather than resolving one.
        //
        // The stale token costs nothing: its cart was deleted, so it finds nothing until the
        // shopper starts a new basket, which is exactly what it is for.

        return ResponseEntity.created(URI.create("/api/orders/track/" + order.traceCode()))
            .body(order);
    }

    /**
     * How a customer checks on an order afterwards.
     *
     * <p>The trace code is the credential — there is no account behind a guest order — which is
     * why it is ten random characters rather than the primary key, and why this is the only way in.
     */
    @GetMapping("/api/orders/track/{traceCode}")
    public ResponseEntity<OrderResponse> track(@PathVariable String traceCode) {
        return ResponseEntity.ok(checkoutService.byTraceCode(traceCode));
    }

    /** The signed-in shopper's own orders. Requires a token; guests use their trace codes. */
    @GetMapping("/api/orders/mine")
    public ResponseEntity<java.util.List<OrderResponse>> mine() {
        return ResponseEntity.ok(checkoutService.forUser(CurrentUser.requiredId()));
    }
}
