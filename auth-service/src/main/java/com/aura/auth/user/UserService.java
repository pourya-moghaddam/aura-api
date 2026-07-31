package com.aura.auth.user;

import com.aura.auth.config.OtpProperties;
import com.aura.common.event.OtpRequestedEvent;
import com.aura.auth.role.Role;
import com.aura.auth.role.RoleRepository;
import com.aura.auth.security.JwtService;
import com.aura.auth.user.dto.*;
import com.aura.auth.user.exception.InvalidCredentialsException;
import com.aura.auth.user.exception.InvalidOtpException;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final StreamBridge streamBridge;
    private final OtpProperties otpProperties;
    private final StringRedisTemplate redisTemplate;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom = new SecureRandom();
    private static final String REDIS_OTP_PREFIX = "otp:phone:";
    private static final String REDIS_BLACKLIST_PREFIX = "jwt:blacklist:";

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

        OtpRequestedEvent event = new OtpRequestedEvent(
            user.getPhone(),
            otpCode,
            String.format(otpProperties.messageTemplate(), otpCode)
        );

        streamBridge.send(otpProperties.otpBindingName(), event);
    }

    @Transactional(readOnly = true)
    public AuthResponse verifyOtp(UserVerificationRequest request) {
        String redisKey = REDIS_OTP_PREFIX + request.phone();
        String savedOtp = redisTemplate.opsForValue().get(redisKey);

        if (savedOtp == null) {
            throw new InvalidOtpException("OTP is expired or invalid.");
        }

        if (!savedOtp.equals(request.otpCode())) {
            throw new InvalidOtpException("Incorrect OTP code.");
        }

        User user = userRepository.findByPhone(request.phone())
            .orElseThrow(() -> new InvalidCredentialsException("User not found after OTP verification"));

        redisTemplate.delete(redisKey);
        String token = jwtService.generateToken(user);

        return new AuthResponse(token);
    }

    @Transactional(readOnly = true)
    public AuthResponse loginWithPassword(PasswordLoginRequest request) {
        User user = userRepository.findByPhone(request.identifier())
            .orElseGet(() -> userRepository.findByEmail(request.identifier())
                .orElseThrow(() -> new InvalidCredentialsException("Invalid credentials")));

        if (user.getPassword() == null) {
            throw new IllegalStateException("No password set for this account. Please log in using OTP.");
        }

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new InvalidCredentialsException("Invalid credentials");
        }

        String token = jwtService.generateToken(user);
        return new AuthResponse(token);
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getUserProfile(String userIdStr) {
        Long userId = Long.getLong(userIdStr);
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new InvalidCredentialsException("User not found"));

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
    public void setPassword(String userIdStr, SetPasswordRequest request) {
        Long userId = Long.getLong(userIdStr);
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new InvalidCredentialsException("User not found"));

        user.setPassword(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
    }

    public void logout(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new IllegalArgumentException("Invalid Authorization header format.");
        }

        String token = authHeader.substring(7);
        long remainingTtl = jwtService.getRemainingExpirationMs(token);

        if (remainingTtl > 0) {
            redisTemplate.opsForValue().set(
                REDIS_BLACKLIST_PREFIX + token,
                "true",
                Duration.ofMillis(remainingTtl)
            );
        }
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
        int code = 100000 + secureRandom.nextInt(900000);
        return String.valueOf(code);
    }
}