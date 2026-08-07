package com.aura.auth.user;

import com.aura.auth.config.OtpProperties;
import com.aura.auth.otp.OtpService;
import com.aura.auth.role.Role;
import com.aura.auth.role.RoleRepository;
import com.aura.auth.security.TokenIssuer;
import com.aura.auth.user.dto.*;
import com.aura.auth.user.exception.InvalidCredentialsException;
import com.aura.common.events.OtpPurpose;
import com.aura.common.events.OtpRequestedEvent;
import com.aura.common.phone.IranianPhoneNumber;
import com.aura.common.security.Roles;
import com.aura.common.security.TokenAudience;
import com.aura.common.security.TokenBlacklistKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final StreamBridge streamBridge;
    private final OtpProperties otpProperties;
    private final OtpService otpService;
    private final StringRedisTemplate redisTemplate;
    private final TokenIssuer tokenIssuer;
    private final PasswordEncoder passwordEncoder;

    // ---------------------------------------------------------------------------------------
    // Storefront
    // ---------------------------------------------------------------------------------------

    /**
     * Issues a code for the storefront. Does <em>not</em> create the user.
     *
     * <p>Creation happens on successful verification instead. Creating on request let anyone fill
     * the users table with arbitrary numbers by looping over an unauthenticated endpoint, and left
     * a permanent row behind for every typo and every probe.
     */
    @Transactional(readOnly = true)
    public void requestStorefrontOtp(UserRegistrationRequest request, String clientIp) {
        String phone = IranianPhoneNumber.normalize(request.phone());
        String code = otpService.issue(phone, clientIp);
        publishOtp(phone, code, OtpPurpose.STOREFRONT_LOGIN);
    }

    @Transactional
    public AuthResponse verifyStorefrontOtp(UserVerificationRequest request) {
        String phone = IranianPhoneNumber.normalize(request.phone());
        otpService.verify(phone, request.otpCode());

        User user = userRepository.findByPhone(phone)
            .orElseGet(() -> createUser(phone));

        return issueFor(user, TokenAudience.STOREFRONT);
    }

    // ---------------------------------------------------------------------------------------
    // Control panel
    // ---------------------------------------------------------------------------------------

    /**
     * Issues a code for the control panel — but only to an existing user holding a control role.
     *
     * <p>Returns normally in every case. An unknown number, a storefront-only user, and a genuine
     * admin are indistinguishable from the outside; only the first two silently skip the SMS.
     * Responding differently would let anyone enumerate which numbers have administrative access,
     * which is precisely the list worth attacking.
     */
    @Transactional(readOnly = true)
    public void requestControlOtp(UserRegistrationRequest request, String clientIp) {
        String phone = IranianPhoneNumber.normalize(request.phone());

        // Limits are consumed regardless, so probing costs the attacker their quota either way.
        String code = otpService.issue(phone, clientIp);

        userRepository.findByPhone(phone)
            .filter(this::hasControlRole)
            .ifPresentOrElse(
                user -> publishOtp(phone, code, OtpPurpose.CONTROL_LOGIN),
                () -> log.info("Control OTP requested for a phone with no control access; no SMS sent"));
    }

    @Transactional(readOnly = true)
    public AuthResponse verifyControlOtp(UserVerificationRequest request) {
        String phone = IranianPhoneNumber.normalize(request.phone());
        otpService.verify(phone, request.otpCode());

        // No create-on-verify here: the control panel has no sign-up.
        User user = userRepository.findByPhone(phone)
            .filter(this::hasControlRole)
            .orElseThrow(() -> new InvalidCredentialsException("Invalid credentials"));

        return issueFor(user, TokenAudience.CONTROL);
    }

    @Transactional(readOnly = true)
    public AuthResponse loginWithPassword(PasswordLoginRequest request, TokenAudience audience) {
        User user = findByIdentifier(request.identifier())
            .orElseThrow(() -> new InvalidCredentialsException("Invalid credentials"));

        // One failure mode for "no password set" and "wrong password": distinguishing them reveals
        // which accounts are OTP-only.
        if (user.getPassword() == null || !passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new InvalidCredentialsException("Invalid credentials");
        }

        if (audience == TokenAudience.CONTROL && !hasControlRole(user)) {
            throw new InvalidCredentialsException("Invalid credentials");
        }

        return issueFor(user, audience);
    }

    /**
     * Tells the UI whether to offer password login for this number.
     *
     * <p>This is a user-existence oracle by construction — that is what the requirement asks for —
     * so it must sit behind the same rate limiting as the OTP endpoints.
     */
    @Transactional(readOnly = true)
    public LoginMethodsResponse availableLoginMethods(String rawPhone) {
        String phone = IranianPhoneNumber.normalize(rawPhone);
        boolean hasPassword = userRepository.findByPhone(phone)
            .map(user -> user.getPassword() != null)
            .orElse(false);
        return new LoginMethodsResponse(true, hasPassword);
    }

    // ---------------------------------------------------------------------------------------
    // Profile
    // ---------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public UserProfileResponse getUserProfile(long userId) {
        User user = requireUser(userId);

        Set<String> roleNames = user.getRoles().stream()
            .map(Role::getName)
            .collect(Collectors.toSet());

        return new UserProfileResponse(
            user.getId(),
            user.getPhone(),
            user.getEmail(),
            user.getIsActive(),
            user.getPassword() != null,
            roleNames,
            user.getCreatedAt()
        );
    }

    @Transactional
    public void setPassword(long userId, SetPasswordRequest request) {
        User user = requireUser(userId);
        user.setPassword(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
    }

    public void logout(Jwt token) {
        Instant expiresAt = token.getExpiresAt();
        if (expiresAt == null) {
            return;
        }

        Duration remaining = Duration.between(Instant.now(), expiresAt);
        if (remaining.isPositive()) {
            redisTemplate.opsForValue().set(
                TokenBlacklistKeys.forTokenId(token.getId()), "1", remaining);
        }
    }

    // ---------------------------------------------------------------------------------------

    private void publishOtp(String phone, String code, OtpPurpose purpose) {
        streamBridge.send(
            otpProperties.otpBindingName(),
            OtpRequestedEvent.of(phone, code, purpose));
    }

    private boolean hasControlRole(User user) {
        return user.getRoles().stream()
            .map(Role::getName)
            .anyMatch(Roles::isControlRole);
    }

    private AuthResponse issueFor(User user, TokenAudience audience) {
        if (!Boolean.TRUE.equals(user.getIsActive())) {
            throw new InvalidCredentialsException("Invalid credentials");
        }
        TokenIssuer.IssuedAccessToken token = tokenIssuer.issue(user, audience);
        return AuthResponse.bearer(token.value(), token.expiresAt());
    }

    private java.util.Optional<User> findByIdentifier(String identifier) {
        return IranianPhoneNumber.tryNormalize(identifier)
            .flatMap(userRepository::findByPhone)
            .or(() -> userRepository.findByEmail(identifier));
    }

    private User requireUser(long userId) {
        return userRepository.findById(userId)
            .orElseThrow(() -> new InvalidCredentialsException("User not found"));
    }

    private User createUser(String normalizedPhone) {
        Role defaultRole = roleRepository.findByName(Roles.USER)
            .orElseThrow(() -> new IllegalStateException("Default role 'USER' not found"));

        User newUser = new User();
        newUser.setPhone(normalizedPhone);
        newUser.getRoles().add(defaultRole);

        return userRepository.save(newUser);
    }
}
