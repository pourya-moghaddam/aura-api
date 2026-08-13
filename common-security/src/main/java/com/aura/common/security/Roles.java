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

    /**
     * The roles an account ends up with, given the ones asked for.
     *
     * <p>{@link #USER} is a baseline, not a privilege: it grants storefront access and nothing
     * more, and control endpoints are gated by the {@code aud=control} audience plus a control
     * role rather than by its absence. So every account gets it, whatever else it holds.
     *
     * <p>Here in one place because there are three ways an account comes into existence — self
     * sign-up, an admin creating one, and the super-admin bootstrap — and they had drifted. The
     * bootstrap super admin was the only account in the system without {@code USER}, which left it
     * unable to sign in to the storefront at all, and made the most privileged account in the
     * system the single exception to "every account has USER" for any code that later assumed so.
     */
    public static Set<String> withBaseline(Set<String> requested) {
        Set<String> roles = new java.util.HashSet<>(requested == null ? Set.<String>of() : requested);
        roles.add(USER);
        return roles;
    }
}
