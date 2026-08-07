package com.aura.common.security;

/**
 * Redis key layout for the force-logout blacklist, shared so auth-service (which writes) and the
 * gateway (which reads) cannot drift apart.
 *
 * <p>Keyed by {@code jti} rather than by the full token string: a jti is 36 bytes and constant,
 * while a serialised JWT is several hundred and varies, which made the previous key both wasteful
 * and impossible to reason about. Entries are written with a TTL matching the token's remaining
 * lifetime, so the set stays small — with 15-minute access tokens it never holds more than
 * 15 minutes of logouts.
 */
public final class TokenBlacklistKeys {

    private static final String PREFIX = "jwt:blacklist:jti:";

    private TokenBlacklistKeys() {
    }

    public static String forTokenId(String tokenId) {
        return PREFIX + tokenId;
    }
}
