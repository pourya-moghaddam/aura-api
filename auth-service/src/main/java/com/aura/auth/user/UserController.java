package com.aura.auth.user;

import com.aura.auth.user.dto.SetPasswordRequest;
import com.aura.auth.user.dto.UserProfileResponse;
import com.aura.common.security.CurrentUser;
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
    public ResponseEntity<Void> logout(@AuthenticationPrincipal Jwt token) {
        userService.logout(token);
        return ResponseEntity.noContent().build();
    }
}
