package com.aura.auth.user;

import com.aura.auth.config.OtpProperties;
import com.aura.auth.role.Role;
import com.aura.auth.role.RoleRepository;
import com.aura.auth.security.TokenIssuer;
import com.aura.auth.user.dto.*;
import com.aura.auth.user.exception.InvalidCredentialsException;
import com.aura.auth.user.exception.InvalidOtpException;
import com.aura.common.events.OtpRequestedEvent;
import com.aura.common.security.TokenAudience;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    private static final String PHONE = "+989120000000";
    private static final String OTP_KEY = "otp:phone:" + PHONE;
    private static final String BINDING = "otpRequestedOut-out-0";

    @Mock
    private UserRepository userRepository;
    @Mock
    private RoleRepository roleRepository;
    @Mock
    private StreamBridge streamBridge;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private TokenIssuer tokenIssuer;
    @Mock
    private PasswordEncoder passwordEncoder;

    private UserService userService;
    private Role userRole;
    private User user;

    @BeforeEach
    void setUp() {
        // A real record rather than a mock: it has no behaviour worth faking, and a real one keeps
        // the test honest about the actual defaults.
        OtpProperties otpProperties = new OtpProperties(3, BINDING);

        userService = new UserService(
            userRepository,
            roleRepository,
            streamBridge,
            otpProperties,
            redisTemplate,
            tokenIssuer,
            passwordEncoder
        );

        userRole = new Role(1L, "USER", "Regular user role");
        user = new User(1L, null, PHONE, null, true, null, null, Set.of(userRole));
    }

    private void stubTokenIssued() {
        when(tokenIssuer.issue(any(User.class), eq(TokenAudience.STOREFRONT)))
            .thenReturn(new TokenIssuer.IssuedAccessToken(
                "generated.jwt.token", "token-id", Instant.now().plusSeconds(900)));
    }

    @Test
    void requestingAnOtpForAnUnknownPhoneCreatesTheUser() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(userRepository.findByPhone(PHONE)).thenReturn(Optional.empty());
        when(roleRepository.findByName("USER")).thenReturn(Optional.of(userRole));
        when(userRepository.save(any(User.class))).thenReturn(user);

        userService.registerOrLogin(new UserRegistrationRequest(PHONE));

        verify(userRepository).save(any(User.class));
        verify(valueOperations).set(eq(OTP_KEY), anyString(), any(Duration.class));
        verify(streamBridge).send(eq(BINDING), any(OtpRequestedEvent.class));
    }

    @Test
    void requestingAnOtpForAKnownPhoneDoesNotCreateAnother() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(userRepository.findByPhone(PHONE)).thenReturn(Optional.of(user));

        userService.registerOrLogin(new UserRegistrationRequest(PHONE));

        verify(userRepository, never()).save(any(User.class));
        verify(valueOperations).set(eq(OTP_KEY), anyString(), any(Duration.class));
    }

    @Test
    void verifyingACorrectOtpIssuesATokenAndBurnsTheCode() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(OTP_KEY)).thenReturn("123456");
        when(userRepository.findByPhone(PHONE)).thenReturn(Optional.of(user));
        stubTokenIssued();

        AuthResponse response = userService.verifyOtp(new UserVerificationRequest(PHONE, "123456"));

        assertThat(response.accessToken()).isEqualTo("generated.jwt.token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        // The code must not survive a successful verification, or it stays replayable until TTL.
        verify(redisTemplate).delete(OTP_KEY);
    }

    @Test
    void verifyingAnExpiredOtpFails() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(OTP_KEY)).thenReturn(null);

        assertThatThrownBy(() -> userService.verifyOtp(new UserVerificationRequest(PHONE, "123456")))
            .isInstanceOf(InvalidOtpException.class);
    }

    @Test
    void verifyingAWrongOtpFails() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(OTP_KEY)).thenReturn("654321");

        assertThatThrownBy(() -> userService.verifyOtp(new UserVerificationRequest(PHONE, "123456")))
            .isInstanceOf(InvalidOtpException.class);
    }

    @Test
    void aDeactivatedUserCannotObtainAToken() {
        User deactivated = new User(2L, null, PHONE, null, false, null, null, Set.of(userRole));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(OTP_KEY)).thenReturn("123456");
        when(userRepository.findByPhone(PHONE)).thenReturn(Optional.of(deactivated));

        assertThatThrownBy(() -> userService.verifyOtp(new UserVerificationRequest(PHONE, "123456")))
            .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void passwordLoginSucceedsWithTheRightPassword() {
        User withPassword = new User(1L, null, PHONE, "encodedPassword", true, null, null, Set.of(userRole));
        when(userRepository.findByPhone(PHONE)).thenReturn(Optional.of(withPassword));
        when(passwordEncoder.matches("password123", "encodedPassword")).thenReturn(true);
        stubTokenIssued();

        AuthResponse response = userService.loginWithPassword(new PasswordLoginRequest(PHONE, "password123"));

        assertThat(response.accessToken()).isEqualTo("generated.jwt.token");
    }

    @Test
    void passwordLoginFailsForAnUnknownIdentifier() {
        when(userRepository.findByPhone(PHONE)).thenReturn(Optional.empty());
        when(userRepository.findByEmail(PHONE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.loginWithPassword(new PasswordLoginRequest(PHONE, "password123")))
            .isInstanceOf(InvalidCredentialsException.class);
    }

    /**
     * An account with no password must fail the same way a wrong password does. Any distinction
     * here tells an attacker which accounts are OTP-only.
     */
    @Test
    void passwordLoginFailsIndistinguishablyWhenNoPasswordIsSet() {
        when(userRepository.findByPhone(PHONE)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userService.loginWithPassword(new PasswordLoginRequest(PHONE, "password123")))
            .isInstanceOf(InvalidCredentialsException.class)
            .hasMessage("Invalid credentials");
    }

    @Test
    void settingAPasswordStoresTheEncodedForm() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("newPassword123")).thenReturn("encodedPassword");

        userService.setPassword(1L, new SetPasswordRequest("newPassword123"));

        verify(userRepository).save(user);
        assertThat(user.getPassword()).isEqualTo("encodedPassword");
    }

    @Test
    void loggingOutBlacklistsTheTokenIdForItsRemainingLifetime() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .jti("the-token-id")
            .issuedAt(now)
            .expiresAt(now.plusSeconds(600))
            .build();

        userService.logout(jwt);

        verify(valueOperations).set(eq("jwt:blacklist:jti:the-token-id"), eq("1"), any(Duration.class));
    }

    @Test
    void loggingOutWithAnAlreadyExpiredTokenWritesNothing() {
        Instant past = Instant.now().minusSeconds(60);
        Jwt jwt = Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .jti("the-token-id")
            .issuedAt(past.minusSeconds(900))
            .expiresAt(past)
            .build();

        userService.logout(jwt);

        verifyNoInteractions(valueOperations);
    }
}
