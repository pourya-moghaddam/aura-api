package com.aura.order.link;

import com.aura.common.security.CurrentUser;
import com.aura.common.security.SellerOnly;
import com.aura.order.link.dto.CreateOrderLinkRequest;
import com.aura.order.link.dto.OrderLinkResponse;
import com.aura.order.order.dto.OrderResponse;
import com.aura.order.payment.dto.PaymentStartResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Seller-composed orders and the links that pay for them.
 *
 * <p>Two surfaces with opposite postures. Creating a link requires a seller's control token; using
 * one requires nothing at all, because the buyer has no account — the token in the URL is the
 * entire credential, which is why it is 256 random bits and stored hashed.
 */
@RestController
@RequiredArgsConstructor
public class SellerOrderLinkController {

    private final SellerOrderLinkService sellerOrderLinkService;

    @PostMapping("/api/control/orders/seller/links")
    @SellerOnly
    public ResponseEntity<OrderLinkResponse> create(
        @Valid @RequestBody CreateOrderLinkRequest request
    ) {
        return ResponseEntity.ok(
            sellerOrderLinkService.create(CurrentUser.requiredId(), request));
    }

    @GetMapping("/api/control/orders/seller/links")
    @SellerOnly
    public ResponseEntity<List<OrderLinkResponse>> list() {
        return ResponseEntity.ok(sellerOrderLinkService.listFor(CurrentUser.requiredId()));
    }

    /** What the buyer sees when they open the link. Anonymous — requirement 12's other half. */
    @GetMapping("/api/orders/links/{token}")
    public ResponseEntity<OrderResponse> view(@PathVariable String token) {
        return ResponseEntity.ok(sellerOrderLinkService.view(token));
    }

    /** Starts the payment and spends the link. */
    @PostMapping("/api/orders/links/{token}/pay")
    public ResponseEntity<PaymentStartResponse> pay(@PathVariable String token) {
        return ResponseEntity.ok(sellerOrderLinkService.pay(token));
    }
}
