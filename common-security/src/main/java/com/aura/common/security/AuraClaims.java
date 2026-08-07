package com.aura.common.security;

/**
 * Custom claim names carried in Aura access tokens.
 */
public final class AuraClaims {

    /** List of unprefixed role names, e.g. {@code ["ADMIN","SELLER"]}. */
    public static final String ROLES = "roles";

    /** Token identifier, used for the short-lived force-logout blacklist. */
    public static final String JWT_ID = "jti";

    private AuraClaims() {
    }
}
