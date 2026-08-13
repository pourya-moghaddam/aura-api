package com.aura.order.discount;

/**
 * Why a code was refused, in words a shopper can act on.
 *
 * <p>An enum rather than an exception thrown from the validator, because the same check serves two
 * callers with opposite needs: the preview endpoint has to <em>report</em> the reason next to the
 * discount box, and redemption has to <em>refuse</em> the checkout. Throwing would force the
 * preview to catch its own control flow.
 *
 * <p>Every reason is deliberately specific except {@link #NOT_FOUND}, which also covers an inactive
 * code — telling a stranger "that code exists but is switched off" is an invitation to guess at the
 * rest.
 */
public enum DiscountRejection {

    NOT_FOUND("discount-not-found", "That code is not valid."),
    NOT_STARTED("discount-not-started", "That code is not active yet."),
    EXPIRED("discount-expired", "That code has expired."),
    EXHAUSTED("discount-exhausted", "That code has been fully redeemed."),
    PER_USER_LIMIT("discount-per-user-limit", "You have already used that code."),
    BELOW_MINIMUM("discount-below-minimum", "Your order does not reach the minimum for that code."),
    NO_EFFECT("discount-no-effect", "That code takes nothing off this order.");

    private final String code;
    private final String message;

    DiscountRejection(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }

    /**
     * The minimum is the one reason worth quantifying — "you need 200,000 more" is actionable in a
     * way that "your order is too small" is not.
     */
    public String messageFor(DiscountCode discount) {
        return this == BELOW_MINIMUM
            ? "This code applies to orders of " + discount.getMinOrderTotal() + " Rial or more."
            : message;
    }
}
