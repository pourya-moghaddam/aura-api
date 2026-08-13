package com.aura.order.discount;

import com.aura.common.security.AdminOnly;
import com.aura.common.security.CurrentUser;
import com.aura.order.cart.CartOwner;
import com.aura.order.cart.CartOwnerResolver;
import com.aura.order.cart.CartService;
import com.aura.order.discount.dto.DiscountCodeRequest;
import com.aura.order.discount.dto.DiscountCodeResponse;
import com.aura.order.discount.dto.DiscountQuoteRequest;
import com.aura.order.discount.dto.DiscountQuoteResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequiredArgsConstructor
public class DiscountController {

    private final DiscountService discountService;
    private final CartService cartService;
    private final CartOwnerResolver cartOwnerResolver;

    // --- checkout ------------------------------------------------------------------------------

    /**
     * "What would this code do to my basket?"
     *
     * <p>Anonymous, because a guest checks out without an account (requirement 12). Answers 200
     * whether the code works or not — a shopper mistyping is an expected outcome, and a 4xx would
     * make the checkout page treat it as a failure rather than as feedback.
     *
     * <p>Quoting reserves nothing. The code is only spent when the order is created, which is why
     * the same rules are checked again there under a lock.
     */
    @PostMapping("/api/orders/discounts/quote")
    public ResponseEntity<DiscountQuoteResponse> quote(
        @Valid @RequestBody DiscountQuoteRequest request,
        HttpServletRequest httpRequest
    ) {
        java.util.List<DiscountLine> lines = cartOwnerResolver.resolveExisting(httpRequest)
            .map(cartService::discountLines)
            .orElseGet(java.util.List::of);

        return ResponseEntity.ok(discountService.quote(request.code(), lines,
            CurrentUser.id().orElse(null)));
    }

    // --- administration ------------------------------------------------------------------------

    @GetMapping("/api/control/orders/discounts")
    @AdminOnly
    public ResponseEntity<Page<DiscountCodeResponse>> list(
        @PageableDefault(size = 20) Pageable pageable
    ) {
        return ResponseEntity.ok(discountService.list(pageable));
    }

    @GetMapping("/api/control/orders/discounts/{id}")
    @AdminOnly
    public ResponseEntity<DiscountCodeResponse> get(@PathVariable long id) {
        return ResponseEntity.ok(discountService.get(id));
    }

    @PostMapping("/api/control/orders/discounts")
    @AdminOnly
    public ResponseEntity<DiscountCodeResponse> create(
        @Valid @RequestBody DiscountCodeRequest request
    ) {
        DiscountCodeResponse created = discountService.create(request);
        return ResponseEntity
            .created(URI.create("/api/control/orders/discounts/" + created.id()))
            .body(created);
    }

    @PutMapping("/api/control/orders/discounts/{id}")
    @AdminOnly
    public ResponseEntity<DiscountCodeResponse> update(
        @PathVariable long id,
        @Valid @RequestBody DiscountCodeRequest request
    ) {
        return ResponseEntity.ok(discountService.update(id, request));
    }

    /**
     * Switches the code off. Not a delete: redemptions reference it, and an order has to stay able
     * to say what discount it was given.
     */
    @DeleteMapping("/api/control/orders/discounts/{id}")
    @AdminOnly
    public ResponseEntity<DiscountCodeResponse> deactivate(@PathVariable long id) {
        return ResponseEntity.ok(discountService.deactivate(id));
    }
}
