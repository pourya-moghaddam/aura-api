package com.aura.auth.user;

import com.aura.auth.config.OtpProperties;
import com.aura.auth.otp.OtpService;
import com.aura.auth.role.Role;
import com.aura.auth.role.RoleRepository;
import com.aura.auth.security.TokenIssuer;
import com.aura.auth.token.AuthSession;
import com.aura.auth.token.RefreshTokenService;
import com.aura.auth.user.dto.*;
import com.aura.auth.user.exception.InvalidCredentialsException;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    /** Deliberately written the way a user would type it, not in canonical form. */
    private static final String TYPED_PHONE = "09121234567";
    private static final String CANONICAL_PHONE = "+989121234567";
    private static final String BINDING = "otpRequestedOut-out-0";
    private static final String CLIENT_IP = "203.0.113.7";
    private static final String RAW_REFRESH_TOKEN = "raw-refresh-token";

    @Mock
    private UserRepository userRepository;
    @Mock
    private RoleRepository roleRepository;
    @Mock
    private StreamBridge streamBridge;
    @Mock
    private OtpService otpService;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private TokenIssuer tokenIssuer;
    @Mock
    private RefreshTokenService refreshTokenService;
    @Mock
    private PasswordEncoder passwordEncoder;

    private UserService userService;
    private Role userRole;
    private Role adminRole;

    @BeforeEach
    void setUp() {
        OtpProperties otpProperties = new OtpProperties(
            Duration.ofMinutes(3), 6, 5, Duration.ofSeconds(60), 5, 20, 2000, "test-pepper", BINDING);

        userService = new UserService(
            userRepository, roleRepository, streamBridge, otpProperties,
            otpService, redisTemplate, tokenIssuer, refreshTokenService, passwordEncoder);

        userRole = new Role(1L, "USER", "Storefront customer");
        adminRole = new Role(2L, "ADMIN", "Catalog administrator");
    }

    private User user(Set<Role> roles) {
        return new User(1L, null, CANONICAL_PHONE, null, true, null, null, roles);
    }

    private void stubToken(TokenAudience audience) {
        when(tokenIssuer.issue(any(User.class), eq(audience)))
            .thenReturn(new TokenIssuer.IssuedAccessToken("jwt", "jti", Instant.now().plusSeconds(900)));
    }

    private void stubRefreshToken(TokenAudience audience) {
        when(refreshTokenService.issueNew(anyLong(), eq(audience)))
            .thenReturn(new RefreshTokenService.IssuedRefreshToken(
                RAW_REFRESH_TOKEN, Instant.now().plusSeconds(2_592_000), 1L, audience));
    }

    // --- Storefront -------------------------------------------------------------------------

    /**
     * The key behaviour change: requesting a code must not create anything. Creating on request
     * let an unauthenticated loop fill the users table with arbitrary numbers.
     */
    @Test
    void requestingAStorefrontCodeDoesNotCreateAUser() {
        when(otpService.issue(CANONICAL_PHONE, CLIENT_IP)).thenReturn("123456");

        userService.requestStorefrontOtp(new UserRegistrationRequest(TYPED_PHONE), CLIENT_IP);

        verify(userRepository, never()).save(any());
        verify(streamBridge).send(eq(BINDING), any(OtpRequestedEvent.class));
    }

    @Test
    void phoneIsNormalisedBeforeItReachesTheOtpService() {
        when(otpService.issue(CANONICAL_PHONE, CLIENT_IP)).thenReturn("123456");

        userService.requestStorefrontOtp(new UserRegistrationRequest("۰۹۱۲۱۲۳۴۵۶۷"), CLIENT_IP);

        // Persian digits in, canonical E.164 out - otherwise this user gets a second account.
        verify(otpService).issue(CANONICAL_PHONE, CLIENT_IP);
    }

    @Test
    void verifyingAStorefrontCodeCreatesTheUserOnFirstLogin() {
        when(userRepository.findByPhone(CANONICAL_PHONE)).thenReturn(Optional.empty());
        when(roleRepository.findByName("USER")).thenReturn(Optional.of(userRole));
        when(userRepository.save(any(User.class))).thenReturn(user(Set.of(userRole)));
        stubToken(TokenAudience.STOREFRONT);
        stubRefreshToken(TokenAudience.STOREFRONT);

        AuthSession session = userService.verifyStorefrontOtp(
            new UserVerificationRequest(TYPED_PHONE, "123456"));

        verify(otpService).verify(CANONICAL_PHONE, "123456");
        verify(userRepository).save(any(User.class));
        assertThat(session.accessToken().accessToken()).isEqualTo("jwt");
        assertThat(session.refreshToken().rawToken()).isEqualTo(RAW_REFRESH_TOKEN);
    }

    @Test
    void verifyingAStorefrontCodeReusesAnExistingUser() {
        when(userRepository.findByPhone(CANONICAL_PHONE)).thenReturn(Optional.of(user(Set.of(userRole))));
        stubToken(TokenAudience.STOREFRONT);
        stubRefreshToken(TokenAudience.STOREFRONT);

        userService.verifyStorefrontOtp(new UserVerificationRequest(TYPED_PHONE, "123456"));

        verify(userRepository, never()).save(any());
    }

    @Test
    void aDeactivatedUserCannotObtainAToken() {
        User deactivated = new User(1L, null, CANONICAL_PHONE, null, false, null, null, Set.of(userRole));
        when(userRepository.findByPhone(CANONICAL_PHONE)).thenReturn(Optional.of(deactivated));

        assertThatThrownBy(() -> userService.verifyStorefrontOtp(
            new UserVerificationRequest(TYPED_PHONE, "123456")))
            .isInstanceOf(InvalidCredentialsException.class);

        verify(refreshTokenService, never()).issueNew(anyLong(), any());
    }

    // --- Control panel ----------------------------------------------------------------------

    @Test
    void controlCodeIsSentToAUserHoldingAControlRole() {
        when(otpService.issue(CANONICAL_PHONE, CLIENT_IP)).thenReturn("123456");
        when(userRepository.findByPhone(CANONICAL_PHONE)).thenReturn(Optional.of(user(Set.of(adminRole))));

        userService.requestControlOtp(new UserRegistrationRequest(TYPED_PHONE), CLIENT_IP);

        verify(streamBridge).send(eq(BINDING), any(OtpRequestedEvent.class));
    }

    /**
     * A storefront-only user must get the same outward response as an admin, with no SMS. Any
     * observable difference lets an attacker enumerate which numbers hold admin access.
     */
    @Test
    void controlCodeIsSilentlyWithheldFromAUserWithoutAControlRole() {
        when(otpService.issue(CANONICAL_PHONE, CLIENT_IP)).thenReturn("123456");
        when(userRepository.findByPhone(CANONICAL_PHONE)).thenReturn(Optional.of(user(Set.of(userRole))));

        userService.requestControlOtp(new UserRegistrationRequest(TYPED_PHONE), CLIENT_IP);

        verify(streamBridge, never()).send(anyString(), any());
    }

    @Test
    void controlCodeRequestForAnUnknownPhoneSucceedsWithoutSendingAnything() {
        when(otpService.issue(CANONICAL_PHONE, CLIENT_IP)).thenReturn("123456");
        when(userRepository.findByPhone(CANONICAL_PHONE)).thenReturn(Optional.empty());

        userService.requestControlOtp(new UserRegistrationRequest(TYPED_PHONE), CLIENT_IP);

        verify(streamBridge, never()).send(anyString(), any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void verifyingAControlCodeIssuesAControlAudienceToken() {
        when(userRepository.findByPhone(CANONICAL_PHONE)).thenReturn(Optional.of(user(Set.of(adminRole))));
        stubToken(TokenAudience.CONTROL);
        stubRefreshToken(TokenAudience.CONTROL);

        AuthSession session = userService.verifyControlOtp(
            new UserVerificationRequest(TYPED_PHONE, "123456"));

        assertThat(session.accessToken().accessToken()).isEqualTo("jwt");
        verify(tokenIssuer).issue(any(User.class), eq(TokenAudience.CONTROL));
        verify(refreshTokenService).issueNew(1L, TokenAudience.CONTROL);
    }

    @Test
    void verifyingAControlCodeFailsForAUserWithoutAControlRole() {
        when(userRepository.findByPhone(CANONICAL_PHONE)).thenReturn(Optional.of(user(Set.of(userRole))));

        assertThatThrownBy(() -> userService.verifyControlOtp(
            new UserVerificationRequest(TYPED_PHONE, "123456")))
            .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void controlCodeVerificationNeverCreatesAUser() {
        when(userRepository.findByPhone(CANONICAL_PHONE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.verifyControlOtp(
            new UserVerificationRequest(TYPED_PHONE, "123456")))
            .isInstanceOf(InvalidCredentialsException.class);
        verify(userRepository, never()).save(any());
    }

    // --- Password login ---------------------------------------------------------------------

    @Test
    void passwordLoginSucceedsWithTheRightPassword() {
        User withPassword = new User(1L, null, CANONICAL_PHONE, "hash", true, null, null, Set.of(userRole));
        when(userRepository.findByPhone(CANONICAL_PHONE)).thenReturn(Optional.of(withPassword));
        when(passwordEncoder.matches("secret", "hash")).thenReturn(true);
        stubToken(TokenAudience.STOREFRONT);
        stubRefreshToken(TokenAudience.STOREFRONT);

        AuthSession session = userService.loginWithPassword(
            new PasswordLoginRequest(TYPED_PHONE, "secret"), TokenAudience.STOREFRONT);

        assertThat(session.accessToken().accessToken()).isEqualTo("jwt");
    }

    /** No password set must be indistinguishable from a wrong password. */
    @Test
    void passwordLoginFailsIdenticallyWhenNoPasswordIsSet() {
        when(userRepository.findByPhone(CANONICAL_PHONE)).thenReturn(Optional.of(user(Set.of(userRole))));

        assertThatThrownBy(() -> userService.loginWithPassword(
            new PasswordLoginRequest(TYPED_PHONE, "secret"), TokenAudience.STOREFRONT))
            .isInstanceOf(InvalidCredentialsException.class)
            .hasMessage("Invalid credentials");
    }

    @Test
    void passwordLoginToTheControlPanelRequiresAControlRole() {
        User plainUser = new User(1L, null, CANONICAL_PHONE, "hash", true, null, null, Set.of(userRole));
        when(userRepository.findByPhone(CANONICAL_PHONE)).thenReturn(Optional.of(plainUser));
        when(passwordEncoder.matches("secret", "hash")).thenReturn(true);

        assertThatThrownBy(() -> userService.loginWithPassword(
            new PasswordLoginRequest(TYPED_PHONE, "secret"), TokenAudience.CONTROL))
            .isInstanceOf(InvalidCredentialsException.class);
    }

    // --- Login methods probe ----------------------------------------------------------------

    @Test
    void loginMethodsReportsWhetherAPasswordIsSet() {
        User withPassword = new User(1L, null, CANONICAL_PHONE, "hash", true, null, null, Set.of(userRole));
        when(userRepository.findByPhone(CANONICAL_PHONE)).thenReturn(Optional.of(withPassword));

        LoginMethodsResponse response = userService.availableLoginMethods(TYPED_PHONE);

        assertThat(response.password()).isTrue();
        assertThat(response.otp()).isTrue();
    }

    @Test
    void loginMethodsForAnUnknownPhoneOffersOtpOnly() {
        when(userRepository.findByPhone(CANONICAL_PHONE)).thenReturn(Optional.empty());

        LoginMethodsResponse response = userService.availableLoginMethods(TYPED_PHONE);

        assertThat(response.password()).isFalse();
        assertThat(response.otp()).isTrue();
    }

    // --- Session refresh ---------------------------------------------------------------------

    @Test
    void refreshingASessionMintsAMatchingAccessToken() {
        User activeUser = user(Set.of(userRole));
        when(refreshTokenService.rotate(RAW_REFRESH_TOKEN)).thenReturn(
            new RefreshTokenService.IssuedRefreshToken(
                "new-raw-token", Instant.now().plusSeconds(2_592_000), 1L, TokenAudience.STOREFRONT));
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser));
        stubToken(TokenAudience.STOREFRONT);

        AuthSession session = userService.refreshSession(RAW_REFRESH_TOKEN);

        assertThat(session.accessToken().accessToken()).isEqualTo("jwt");
        assertThat(session.refreshToken().rawToken()).isEqualTo("new-raw-token");
        verify(tokenIssuer).issue(activeUser, TokenAudience.STOREFRONT);
    }

    @Test
    void refreshingWithADeactivatedUserFails() {
        User deactivated = new User(1L, null, CANONICAL_PHONE, null, false, null, null, Set.of(userRole));
        when(refreshTokenService.rotate(RAW_REFRESH_TOKEN)).thenReturn(
            new RefreshTokenService.IssuedRefreshToken(
                "new-raw-token", Instant.now().plusSeconds(2_592_000), 1L, TokenAudience.STOREFRONT));
        when(userRepository.findById(1L)).thenReturn(Optional.of(deactivated));

        assertThatThrownBy(() -> userService.refreshSession(RAW_REFRESH_TOKEN))
            .isInstanceOf(InvalidCredentialsException.class);
    }

    // --- Logout / password change -------------------------------------------------------------

    @Test
    void loggingOutRevokesTheRefreshFamilyAndBlacklistsTheAccessToken() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .jti("the-token-id")
            .issuedAt(now)
            .expiresAt(now.plusSeconds(600))
            .build();

        userService.logout(jwt, RAW_REFRESH_TOKEN);

        verify(valueOperations).set(eq("jwt:blacklist:jti:the-token-id"), eq("1"), any(Duration.class));
        verify(refreshTokenService).revokeFamilyContaining(RAW_REFRESH_TOKEN);
    }

    @Test
    void loggingOutWithNoRefreshCookiePresentStillBlacklistsTheAccessToken() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .jti("the-token-id")
            .issuedAt(now)
            .expiresAt(now.plusSeconds(600))
            .build();

        userService.logout(jwt, null);

        verify(valueOperations).set(anyString(), anyString(), any(Duration.class));
        verify(refreshTokenService, never()).revokeFamilyContaining(any());
    }

    @Test
    void settingAPasswordRevokesEveryExistingSession() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(Set.of(userRole))));
        when(passwordEncoder.encode("newPassword123")).thenReturn("encoded");

        userService.setPassword(1L, new SetPasswordRequest("newPassword123"));

        verify(refreshTokenService).revokeAllForUser(1L);
    }
}
