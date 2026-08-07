package com.aura.auth.token;

import com.aura.auth.user.dto.AuthResponse;

/**
 * The result of any login or refresh: an access token for the response body, and a refresh token
 * for the cookie. Kept as one object so a service method cannot return one without the other.
 */
public record AuthSession(AuthResponse accessToken, RefreshTokenService.IssuedRefreshToken refreshToken) {
}
