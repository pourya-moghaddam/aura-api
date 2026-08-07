package com.aura.auth.user;

import com.aura.auth.config.OtpProperties;
import com.aura.auth.role.Role;
import com.aura.auth.role.RoleRepository;
import com.aura.auth.security.TokenIssuer;
import com.aura.auth.user.dto.*;
import com.aura.auth.user.exception.InvalidCredentialsException;
import com.aura.auth.user.exception.InvalidOtpException;
import com.aura.common.events.OtpPurpose;
import com.aura.common.events.OtpRequestedEvent;
import com.aura.common.security.TokenAudience;
import com.aura.common.security.TokenBlacklistKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserService {

    private static final String REDIS_OTP_PREFIX = "otp:phone:";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final StreamBridge streamBridge;
    private final OtpProperties otpProperties;
    private final StringRedisTemplate redisTemplate;
    private final TokenIssuer tokenIssuer;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public void registerOrLogin(UserRegistrationRequest request) {
        User user = userRepository.findByPhone(request.phone())
            .orElseGet(() -> createNewUser(request));

        String otpCode = generateOtp();

        redisTemplate.opsForValue().set(
            REDIS_OTP_PREFIX + user.getPhone(),
            otpCode,
            Duration.ofMinutes(otpProperties.ttlMinutes())
        );

        streamBridge.send(
            otpProperties.otpBindingName(),
            OtpRequestedEvent.of(user.getPhone(), otpCode, OtpPurpose.STOREFRONT_LOGIN)
        );
    }

    @Transactional(readOnly = true)
    public AuthResponse verifyOtp(UserVerificationRequest request) {
        String redisKey = REDIS_OTP_PREFIX + request.phone();
        String savedOtp = redisTemplate.opsForValue().get(redisKey);

        if (savedOtp == null || !savedOtp.equals(request.otpCode())) {
            throw new InvalidOtpException("The code is incorrect or has expired.");
        }

        User user = userRepository.findByPhone(request.phone())
            .orElseThrow(() -> new InvalidCredentialsException("Invalid credentials"));

        redisTemplate.delete(redisKey);
        return issueFor(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse loginWithPassword(PasswordLoginRequest request) {
        User user = userRepository.findByPhone(request.identifier())
            .or(() -> userRepository.findByEmail(request.identifier()))
            .orElseThrow(() -> new InvalidCredentialsException("Invalid credentials"));

        // Same error for "no password set" as for "wrong password": distinguishing them tells an
        // attacker which accounts are OTP-only, which is free reconnaissance.
        if (user.getPassword() == null || !passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new InvalidCredentialsException("Invalid credentials");
        }

        return issueFor(user);
    }

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

    /**
     * Blacklists the presented token for whatever is left of its lifetime.
     *
     * <p>With 15-minute access tokens this is a small, self-expiring set. Real revocation arrives
     * in Phase 1 with refresh-token families; this covers the "log me out everywhere, now" case in
     * the interim.
     */
    public void logout(Jwt token) {
        Instant expiresAt = token.getExpiresAt();
        if (expiresAt == null) {
            return;
        }

        Duration remaining = Duration.between(Instant.now(), expiresAt);
        if (remaining.isPositive()) {
            redisTemplate.opsForValue().set(
                TokenBlacklistKeys.forTokenId(token.getId()),
                "1",
                remaining
            );
        }
    }

    private AuthResponse issueFor(User user) {
        if (!Boolean.TRUE.equals(user.getIsActive())) {
            throw new InvalidCredentialsException("Invalid credentials");
        }
        TokenIssuer.IssuedAccessToken token = tokenIssuer.issue(user, TokenAudience.STOREFRONT);
        return AuthResponse.bearer(token.value(), token.expiresAt());
    }

    private User requireUser(long userId) {
        return userRepository.findById(userId)
            .orElseThrow(() -> new InvalidCredentialsException("User not found"));
    }

    private User createNewUser(UserRegistrationRequest request) {
        Role defaultRole = roleRepository.findByName("USER")
            .orElseThrow(() -> new IllegalStateException("Default role 'USER' not found"));

        User newUser = new User();
        newUser.setPhone(request.phone());
        newUser.getRoles().add(defaultRole);

        return userRepository.save(newUser);
    }

    private String generateOtp() {
        return String.valueOf(100000 + secureRandom.nextInt(900000));
    }
}
