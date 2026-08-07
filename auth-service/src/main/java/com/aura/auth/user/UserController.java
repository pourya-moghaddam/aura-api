package com.aura.auth.user;

import com.aura.auth.config.TokenProperties;
import com.aura.auth.token.RefreshTokenCookie;
import com.aura.auth.user.dto.SetPasswordRequest;
import com.aura.auth.user.dto.UserProfileResponse;
import com.aura.common.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * The authenticated user's own profile. Authentication itself lives in {@link AuthController}.
 */
@RestController
@RequestMapping("/api/auth/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final TokenProperties tokenProperties;

    @GetMapping("/me")
    public ResponseEntity<UserProfileResponse> getCurrentUserProfile() {
        return ResponseEntity.ok(userService.getUserProfile(CurrentUser.requiredId()));
    }

    @PostMapping("/password")
    public ResponseEntity<Void> setPassword(@Valid @RequestBody SetPasswordRequest request) {
        userService.setPassword(CurrentUser.requiredId(), request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
        @AuthenticationPrincipal Jwt token,
        HttpServletRequest httpRequest,
        HttpServletResponse httpResponse
    ) {
        // The cookie may legitimately be absent (e.g. already expired), so this is read leniently
        // rather than via RefreshTokenCookie.readOrThrow - logout must succeed either way.
        String presentedRefreshToken = readRefreshTokenOrNull(httpRequest);
        userService.logout(token, presentedRefreshToken);
        RefreshTokenCookie.clear(httpResponse, tokenProperties.cookieSecure());
        return ResponseEntity.noContent().build();
    }

    private String readRefreshTokenOrNull(HttpServletRequest request) {
        var cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (var cookie : cookies) {
            if (RefreshTokenCookie.NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
