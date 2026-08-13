package com.aura.order.payment.zarinpal;

/**
 * The two calls Zarinpal's gateway is made of.
 *
 * <p>An interface so the payment rules can be tested without a gateway, and so the mock used in
 * development is the same shape as the real thing rather than a branch inside the service.
 */
public interface ZarinpalClient {

    /**
     * Asks for an authority — Zarinpal's handle for one payment attempt.
     *
     * @param amount Rial, as a whole number
     */
    RequestResult request(long amount, String description, String callbackUrl,
                          String mobile, String orderId);

    /**
     * Confirms, server-side, that the money actually moved.
     *
     * <p>The only thing that proves a payment. {@code Status=OK} on the callback is a query
     * parameter the shopper's own browser carries and can be typed by hand.
     */
    VerifyResult verify(long amount, String authority);

    /**
     * @param code      100 on success
     * @param authority quoted back at verify and used to build the redirect
     * @param raw       the response as it arrived, for the audit trail
     */
    record RequestResult(int code, String authority, String message, String raw) {

        public boolean isSuccess() {
            return code == 100 && authority != null && !authority.isBlank();
        }
    }

    /**
     * @param code 100 for a payment verified now, 101 for one verified before
     */
    record VerifyResult(int code, String refId, String cardPan, String message, String raw) {

        /**
         * Both 100 and 101 mean the money is ours.
         *
         * <p>101 is "already verified", which is a success and not an error — reading it as a
         * failure is how a paid order is cancelled and the customer's stock released underneath
         * them. It is also the ordinary outcome whenever the callback and the reconciliation sweep
         * both reach the same payment.
         */
        public boolean isPaid() {
            return code == 100 || code == 101;
        }

        public boolean isAlreadyVerified() {
            return code == 101;
        }
    }
}
