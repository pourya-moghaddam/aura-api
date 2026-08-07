package com.aura.auth.user;

import com.aura.auth.user.dto.*;
import com.aura.common.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping("/request-otp")
    public ResponseEntity<Void> requestOtp(@Valid @RequestBody UserRegistrationRequest request) {
        userService.registerOrLogin(request);
        // 202: the code has been queued for delivery, not necessarily delivered yet.
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/verify-otp")
    public ResponseEntity<AuthResponse> verifyOtp(@Valid @RequestBody UserVerificationRequest request) {
        return ResponseEntity.ok(userService.verifyOtp(request));
    }

    @PostMapping("/login/password")
    public ResponseEntity<AuthResponse> loginWithPassword(@Valid @RequestBody PasswordLoginRequest request) {
        return ResponseEntity.ok(userService.loginWithPassword(request));
    }

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
