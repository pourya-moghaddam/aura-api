package com.aura.common.security;

import java.util.Set;

/**
 * Role names, matching the {@code roles} table seeded by auth-service migration V1.
 *
 * <p>Names are unprefixed. Spring's {@code hasRole()} adds the {@code ROLE_} prefix itself, so
 * these are what you pass to {@code hasRole(...)}; {@link #authority(String)} produces the prefixed
 * form for {@code hasAuthority(...)}.
 */
public final class Roles {

    public static final String SUPER_ADMIN = "SUPER_ADMIN";
    public static final String ADMIN = "ADMIN";
    public static final String SELLER = "SELLER";
    public static final String USER = "USER";

    public static final String AUTHORITY_PREFIX = "ROLE_";

    /**
     * Roles that grant access to the control panel. A user holding none of these can authenticate
     * against the storefront but must not be issued a control-audience token.
     */
    public static final Set<String> CONTROL_ROLES = Set.of(SUPER_ADMIN, ADMIN, SELLER);

    private Roles() {
    }

    public static String authority(String role) {
        return AUTHORITY_PREFIX + role;
    }

    public static boolean isControlRole(String role) {
        return CONTROL_ROLES.contains(role);
    }
}
