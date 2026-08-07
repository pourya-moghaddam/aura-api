package com.aura.auth.user;

import com.aura.auth.user.dto.*;
import com.aura.common.security.TokenAudience;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Authentication for both surfaces.
 *
 * <p>Storefront and control are separate paths rather than a flag on one endpoint, because the
 * rules genuinely differ: storefront creates accounts, control never does and additionally
 * requires a control role. Collapsing them into one handler with a branch is how the "no sign-up
 * on the admin panel" rule eventually gets lost.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    // --- Storefront -------------------------------------------------------------------------

    @PostMapping("/storefront/otp/request")
    public ResponseEntity<Void> requestStorefrontOtp(
        @Valid @RequestBody UserRegistrationRequest request,
        HttpServletRequest httpRequest
    ) {
        userService.requestStorefrontOtp(request, ClientIp.of(httpRequest));
        // 202: queued for delivery, not confirmed delivered.
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/storefront/otp/verify")
    public ResponseEntity<AuthResponse> verifyStorefrontOtp(
        @Valid @RequestBody UserVerificationRequest request
    ) {
        return ResponseEntity.ok(userService.verifyStorefrontOtp(request));
    }

    @PostMapping("/storefront/login")
    public ResponseEntity<AuthResponse> storefrontPasswordLogin(
        @Valid @RequestBody PasswordLoginRequest request
    ) {
        return ResponseEntity.ok(userService.loginWithPassword(request, TokenAudience.STOREFRONT));
    }

    // --- Control panel ----------------------------------------------------------------------

    @PostMapping("/control/otp/request")
    public ResponseEntity<Void> requestControlOtp(
        @Valid @RequestBody UserRegistrationRequest request,
        HttpServletRequest httpRequest
    ) {
        userService.requestControlOtp(request, ClientIp.of(httpRequest));
        // Always 202, whether or not an SMS was actually sent — see UserService#requestControlOtp.
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/control/otp/verify")
    public ResponseEntity<AuthResponse> verifyControlOtp(
        @Valid @RequestBody UserVerificationRequest request
    ) {
        return ResponseEntity.ok(userService.verifyControlOtp(request));
    }

    @PostMapping("/control/login")
    public ResponseEntity<AuthResponse> controlPasswordLogin(
        @Valid @RequestBody PasswordLoginRequest request
    ) {
        return ResponseEntity.ok(userService.loginWithPassword(request, TokenAudience.CONTROL));
    }

    // --- Shared -----------------------------------------------------------------------------

    /**
     * Lets the UI default to password entry when the account has one.
     *
     * <p>POST rather than GET so the phone number stays out of URLs, access logs, and referrers.
     */
    @PostMapping("/login-methods")
    public ResponseEntity<LoginMethodsResponse> loginMethods(
        @Valid @RequestBody UserRegistrationRequest request
    ) {
        return ResponseEntity.ok(userService.availableLoginMethods(request.phone()));
    }
}
