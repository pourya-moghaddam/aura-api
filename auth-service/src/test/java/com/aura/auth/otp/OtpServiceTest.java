package com.aura.auth.otp;

import com.aura.auth.config.OtpProperties;
import com.aura.auth.user.exception.InvalidOtpException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OtpServiceTest {

    private static final String PHONE = "+989121234567";
    private static final String CODE_KEY = "otp:code:" + PHONE;
    private static final String ATTEMPTS_KEY = "otp:attempts:" + PHONE;

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private OtpRateLimiter rateLimiter;

    private OtpService otpService;

    @BeforeEach
    void setUp() {
        OtpProperties properties = new OtpProperties(
            Duration.ofMinutes(3), 6, 5, Duration.ofSeconds(60), 5, 20, 2000,
            "a-fixed-test-pepper", "binding");

        otpService = new OtpService(redis, properties, rateLimiter, new MockEnvironment());
        when(redis.opsForValue()).thenReturn(valueOps);
    }

    /** Captures whatever the service wrote to Redis under the code key. */
    private String storedValue() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq(CODE_KEY), captor.capture(), any(Duration.class));
        return captor.getValue();
    }

    @Test
    void issuesACodeOfTheConfiguredLength() {
        String code = otpService.issue(PHONE, "203.0.113.7");

        assertThat(code).hasSize(6).containsOnlyDigits();
        verify(rateLimiter).checkAndConsume(PHONE, "203.0.113.7");
    }

    /**
     * The stored value must not be the code. Anyone able to read Redis — an operator, a backup, a
     * stray export — could otherwise log in as any user who is mid-login.
     */
    @Test
    void storesAHashRatherThanThePlaintextCode() {
        String code = otpService.issue(PHONE, null);

        String stored = storedValue();
        assertThat(stored).isNotEqualTo(code).doesNotContain(code);
        // HMAC-SHA256 rendered as hex.
        assertThat(stored).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    void issuingClearsAnyPreviousAttemptCount() {
        otpService.issue(PHONE, null);

        // Otherwise a user who fumbled the last code would find the fresh one already half-burned.
        verify(redis).delete(ATTEMPTS_KEY);
    }

    @Test
    void acceptsTheCorrectCodeAndBurnsIt() {
        String code = otpService.issue(PHONE, null);
        // Captured before stubbing: storedValue() runs a verify(), and Mockito rejects that
        // inside a when(...) argument as an unfinished stubbing.
        String storedHash = storedValue();
        when(valueOps.get(CODE_KEY)).thenReturn(storedHash);
        when(valueOps.increment(ATTEMPTS_KEY)).thenReturn(1L);

        assertThatCode(() -> otpService.verify(PHONE, code)).doesNotThrowAnyException();

        // A verified code must not remain usable for the rest of its TTL.
        verify(redis).delete(CODE_KEY);
    }

    @Test
    void rejectsAWrongCodeWithoutBurningTheRemainingAttempts() {
        otpService.issue(PHONE, null);
        // Captured before stubbing: storedValue() runs a verify(), and Mockito rejects that
        // inside a when(...) argument as an unfinished stubbing.
        String storedHash = storedValue();
        when(valueOps.get(CODE_KEY)).thenReturn(storedHash);
        when(valueOps.increment(ATTEMPTS_KEY)).thenReturn(2L);

        assertThatThrownBy(() -> otpService.verify(PHONE, "000000"))
            .isInstanceOf(InvalidOtpException.class);

        verify(redis, never()).delete(CODE_KEY);
    }

    /** The cap is what makes a six-digit secret non-trivial to brute-force. */
    @Test
    void burnsTheCodeOnceTheAttemptCapIsReached() {
        otpService.issue(PHONE, null);
        // Captured before stubbing: storedValue() runs a verify(), and Mockito rejects that
        // inside a when(...) argument as an unfinished stubbing.
        String storedHash = storedValue();
        when(valueOps.get(CODE_KEY)).thenReturn(storedHash);
        when(valueOps.increment(ATTEMPTS_KEY)).thenReturn(6L);

        assertThatThrownBy(() -> otpService.verify(PHONE, "000000"))
            .isInstanceOf(InvalidOtpException.class);

        verify(redis).delete(CODE_KEY);
        // Twice: once when issue() cleared the previous count, once when burn() wiped it.
        verify(redis, times(2)).delete(ATTEMPTS_KEY);
    }

    @Test
    void rejectsWhenNoCodeWasEverIssued() {
        when(valueOps.get(CODE_KEY)).thenReturn(null);

        assertThatThrownBy(() -> otpService.verify(PHONE, "123456"))
            .isInstanceOf(InvalidOtpException.class);
    }

    /**
     * Expired, wrong, and never-issued must be indistinguishable. Any difference is an oracle for
     * probing which numbers have a login in flight.
     */
    @Test
    void reportsEveryFailureIdentically() {
        when(valueOps.get(CODE_KEY)).thenReturn(null);
        String neverIssued = catchMessage(() -> otpService.verify(PHONE, "123456"));

        otpService.issue(PHONE, null);
        // Captured before stubbing: storedValue() runs a verify(), and Mockito rejects that
        // inside a when(...) argument as an unfinished stubbing.
        String storedHash = storedValue();
        when(valueOps.get(CODE_KEY)).thenReturn(storedHash);
        when(valueOps.increment(ATTEMPTS_KEY)).thenReturn(1L);
        String wrongCode = catchMessage(() -> otpService.verify(PHONE, "000000"));

        assertThat(neverIssued).isEqualTo(wrongCode);
    }

    @Test
    void expiresTheAttemptCounterAlongsideTheCode() {
        otpService.issue(PHONE, null);
        // Captured before stubbing: storedValue() runs a verify(), and Mockito rejects that
        // inside a when(...) argument as an unfinished stubbing.
        String storedHash = storedValue();
        when(valueOps.get(CODE_KEY)).thenReturn(storedHash);
        when(valueOps.increment(ATTEMPTS_KEY)).thenReturn(1L);

        assertThatThrownBy(() -> otpService.verify(PHONE, "000000"))
            .isInstanceOf(InvalidOtpException.class);

        // A counter outliving its code would poison the next code issued to this number.
        verify(redis).expire(ATTEMPTS_KEY, Duration.ofMinutes(3));
    }

    private String catchMessage(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected the verification to fail");
        } catch (InvalidOtpException e) {
            return e.getMessage();
        }
    }
}
