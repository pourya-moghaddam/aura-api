package com.aura.order.order;

import com.aura.common.security.AdminOnly;
import com.aura.common.web.PageResponse;
import com.aura.order.order.dto.AdminOrderResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Every order in the shop — requirement 8's administrative counterpart.
 *
 * <p>{@link SellerOrderController} deliberately shows a seller only their own lines, and says so:
 * "an administrative view of every order is a different screen with different needs". This is that
 * screen. An admin reaching the seller endpoints is treated as a seller with no products and sees
 * nothing, which is correct there and useless here.
 *
 * <p>Read-only, deliberately. Advancing a line is the seller's act, and an admin quietly marking
 * someone else's parcel shipped would leave the seller's own screen disagreeing with reality. If
 * an administrative override is ever wanted it should be an explicit, audited action rather than
 * the same button.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/control/orders")
public class AdminOrderController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AdminOrderService adminOrderService;

    /**
     * @param paymentStatus optional filter. Unpaid orders are included by default, which is the
     *                      difference between this and a seller's list — a stalled payment is
     *                      exactly what an administrator is looking for.
     */
    @GetMapping
    @AdminOnly
    public ResponseEntity<PageResponse<AdminOrderResponse>> list(
        @RequestParam(required = false) PaymentStatus paymentStatus,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        int boundedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        var pageable = PageRequest.of(Math.max(page, 0), boundedSize);

        return ResponseEntity.ok(
            PageResponse.of(adminOrderService.list(paymentStatus, pageable)));
    }

    /**
     * One order in full.
     *
     * <p>The {@code \d+} constraint is load-bearing, not decoration. This path shares its prefix
     * with three literal siblings — {@code /seller}, {@code /discounts} and
     * {@code /delivery-methods} — and while Spring prefers a literal over a template, an
     * unconstrained {@code {orderId}} would still be a trap for the next path added here.
     */
    @GetMapping("/{orderId:\\d+}")
    @AdminOnly
    public ResponseEntity<AdminOrderResponse> get(@PathVariable long orderId) {
        return ResponseEntity.ok(adminOrderService.get(orderId));
    }
}
