package com.aura.auth.user.dto;

import java.time.Instant;

/**
 * @param expiresAt when the access token stops being accepted, so clients can refresh ahead of
 *                  expiry instead of discovering it through a failed request
 */
public record AuthResponse(String accessToken, String tokenType, Instant expiresAt) {

    public static AuthResponse bearer(String accessToken, Instant expiresAt) {
        return new AuthResponse(accessToken, "Bearer", expiresAt);
    }
}
