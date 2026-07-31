package com.aura.auth.user;

import com.aura.auth.user.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping("/request-otp")
    public ResponseEntity<String> requestOtp(@Valid @RequestBody UserRegistrationRequest request) {
        userService.registerOrLogin(request);
        return ResponseEntity.status(HttpStatus.OK).body("OTP sent successfully");
    }

    @PostMapping("/verify-otp")
    public ResponseEntity<AuthResponse> verifyOtp(@Valid @RequestBody UserVerificationRequest request) {
        AuthResponse response = userService.verifyOtp(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/login/password")
    public ResponseEntity<AuthResponse> loginWithPassword(@Valid @RequestBody PasswordLoginRequest request) {
        AuthResponse response = userService.loginWithPassword(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/me")
    public ResponseEntity<UserProfileResponse> getCurrentUserProfile(Authentication authentication) {
        String userId = (String) authentication.getPrincipal();
        UserProfileResponse response = userService.getUserProfile(userId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/password")
    public ResponseEntity<String> setPassword(
        Authentication authentication,
        @Valid @RequestBody SetPasswordRequest request
    ) {
        String userId = (String) authentication.getPrincipal();
        userService.setPassword(userId, request);
        return ResponseEntity.ok("Password set successfully");
    }

    @PostMapping("/logout")
    public ResponseEntity<String> logout(@RequestHeader("Authorization") String authHeader) {
        userService.logout(authHeader);
        return ResponseEntity.ok("Logged out successfully");
    }
}