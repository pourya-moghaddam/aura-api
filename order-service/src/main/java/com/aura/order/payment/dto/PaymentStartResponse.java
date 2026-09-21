package com.aura.order.payment.dto;

/**
 * Where to send the shopper to pay.
 *
 * <p>The client redirects to {@code redirectUrl} rather than the server issuing a 302, so a
 * single-page storefront can show its own "taking you to your bank" state first — and so this
 * endpoint stays an ordinary JSON call that can be retried and inspected.
 */
public record PaymentStartResponse(
    String traceCode,
    String authority,
    String redirectUrl,
    Long amount
) {
}
