package com.aura.order.payment.zarinpal;

/**
 * The gateway could not be reached or could not be understood.
 *
 * <p>Distinct from a refusal on purpose. A refusal is an answer — the payment failed, settle it
 * and release the stock. This is the absence of an answer, and the only safe response to it is to
 * leave the payment pending for reconciliation to ask again.
 */
public class ZarinpalUnavailableException extends RuntimeException {

    public ZarinpalUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
