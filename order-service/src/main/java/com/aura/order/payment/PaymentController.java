package com.aura.order.payment;

import com.aura.order.order.PaymentStatus;
import com.aura.order.payment.dto.PaymentStartResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Starting a payment and handling the shopper's return from the gateway.
 *
 * <p>Both anonymous. A guest checks out without an account (requirement 12), and the callback is
 * a plain browser redirect from Zarinpal carrying no credential of ours at all — which is exactly
 * why nothing it says is believed without a server-side verify.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    /**
     * Starts an attempt for an order and answers with where to send the shopper.
     *
     * <p>The trace code identifies the order because it is the only handle a guest has. It is
     * unguessable by design, and starting a payment for someone else's order would only pay for
     * their goods, so possession of the code is sufficient authority here.
     */
    @PostMapping("/api/orders/payments/start/{traceCode}")
    public ResponseEntity<PaymentStartResponse> start(@PathVariable String traceCode) {
        return ResponseEntity.ok(paymentService.start(traceCode));
    }

    /**
     * Where Zarinpal returns the shopper.
     *
     * <p>Redirects to the storefront rather than answering with JSON, because what arrives here is
     * a person in a browser, not a client making an API call. The outcome is settled first — this
     * is a GET that deliberately changes state, because the gateway offers no other hook.
     *
     * <p>Always redirects, even when something goes wrong. A shopper who has just paid must never
     * be shown a stack trace or a bare error page; the storefront result page can explain.
     */
    @GetMapping("/api/orders/payments/callback")
    public ResponseEntity<Void> callback(
        @RequestParam("Authority") String authority,
        @RequestParam("Status") String status
    ) {
        Payment payment;
        try {
            payment = paymentService.settleCallback(authority, status);
        } catch (RuntimeException e) {
            // Verification could not be completed - the gateway is unreachable, or the authority
            // is unknown. The payment stays pending and reconciliation will settle it; the shopper
            // is told it is being confirmed rather than shown a failure that may not be one.
            log.error("Callback for authority {} could not be settled", authority, e);
            return redirectTo(paymentService.resultUrlPending(authority));
        }

        return redirectTo(paymentService.resultUrlFor(payment));
    }

    /** The attempts made against an order, for a support enquiry or a payment-status poll. */
    @GetMapping("/api/orders/track/{traceCode}/payment")
    public ResponseEntity<PaymentStatus> paymentStatus(@PathVariable String traceCode) {
        return ResponseEntity.ok(paymentService.statusOf(traceCode));
    }

    private ResponseEntity<Void> redirectTo(String url) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
    }
}
