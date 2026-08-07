package com.aura.common.security;

/**
 * Which surface a token was minted for.
 *
 * <p>This is the mechanism behind the control-panel access rule: a token issued through the
 * storefront login carries {@link #STOREFRONT} and is rejected on control routes even if the user
 * happens to hold {@code ADMIN}. A user must authenticate through the control login — where the
 * role check happens — to get a {@link #CONTROL} token.
 */
public enum TokenAudience {

    STOREFRONT("storefront"),
    CONTROL("control");

    private final String value;

    TokenAudience(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /** Authority granted to a token bearing this audience, e.g. {@code AUD_control}. */
    public String authority() {
        return "AUD_" + value;
    }

    public static TokenAudience fromValue(String value) {
        for (TokenAudience audience : values()) {
            if (audience.value.equals(value)) {
                return audience;
            }
        }
        throw new IllegalArgumentException("Unknown token audience: " + value);
    }
}
