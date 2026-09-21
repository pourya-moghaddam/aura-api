package com.aura.order.order;

import com.aura.common.security.CurrentUser;
import com.aura.common.security.SellerOnly;
import com.aura.common.web.PageResponse;
import com.aura.order.order.dto.SellerOrderResponse;
import com.aura.order.order.dto.UpdateItemStatusRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A seller's own orders — requirement 8.
 *
 * <p>The seller id comes from the token, never from the path or the body. A seller id in the URL
 * would be an invitation to type someone else's, and the whole point of this surface is that one
 * seller cannot see another's business.
 *
 * <p>An admin reaching these endpoints is treated as a seller with no products, so they see
 * nothing. That is deliberate rather than an oversight: an administrative view of every order is a
 * different screen with different needs, and quietly widening this one would mean the same code
 * path sometimes filters by ownership and sometimes does not.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/control/orders/seller")
public class SellerOrderController {

    private final SellerOrderService sellerOrderService;

    /**
     * {@link PageResponse}, like the rest of the control surface, rather than Spring's own
     * {@code Page}. The two disagree about the page number — {@code number} against {@code page} —
     * so a client written against one paginates from zero forever against the other, without
     * anything failing loudly enough to notice.
     */
    @GetMapping
    @SellerOnly
    public ResponseEntity<PageResponse<SellerOrderResponse>> list(
        @PageableDefault(size = 20) Pageable pageable
    ) {
        return ResponseEntity.ok(
            PageResponse.of(sellerOrderService.list(CurrentUser.requiredId(), pageable)));
    }

    @GetMapping("/{orderId}")
    @SellerOnly
    public ResponseEntity<SellerOrderResponse> get(@PathVariable long orderId) {
        return ResponseEntity.ok(sellerOrderService.get(CurrentUser.requiredId(), orderId));
    }

    /**
     * Advances one line.
     *
     * <p>PUT rather than POST on a verb: the status is a property of the line and the request sets
     * it, which also makes a repeated request harmless — a seller double-clicking "mark shipped"
     * should not be an error.
     */
    @PutMapping("/{orderId}/items/{itemId}/status")
    @SellerOnly
    public ResponseEntity<SellerOrderResponse> advance(
        @PathVariable long orderId,
        @PathVariable long itemId,
        @Valid @RequestBody UpdateItemStatusRequest request
    ) {
        return ResponseEntity.ok(sellerOrderService.advance(
            CurrentUser.requiredId(), orderId, itemId, request.status()));
    }
}
