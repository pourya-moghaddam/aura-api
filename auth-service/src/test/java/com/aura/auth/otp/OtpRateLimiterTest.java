package com.aura.auth.otp;

import com.aura.auth.config.OtpProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OtpRateLimiterTest {

    private static final String PHONE = "+989121234567";
    private static final String IP = "203.0.113.7";

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;

    private OtpRateLimiter limiter;

    @BeforeEach
    void setUp() {
        OtpProperties properties = new OtpProperties(
            Duration.ofMinutes(3), 6, 5, Duration.ofSeconds(60),
            5,      // per phone per hour
            20,     // per ip per hour
            2000,   // global per day
            "pepper", "binding");

        limiter = new OtpRateLimiter(redis, properties);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.hasKey(anyString())).thenReturn(false);
        when(valueOps.increment(anyString())).thenReturn(1L);
    }

    @Test
    void allowsARequestWithinEveryLimit() {
        assertThatCode(() -> limiter.checkAndConsume(PHONE, IP)).doesNotThrowAnyException();
        // The cooldown is only armed once the request is actually permitted.
        verify(valueOps).set(contains("cooldown"), eq("1"), any(Duration.class));
    }

    @Test
    void rejectsWhileTheCooldownIsActive() {
        when(redis.hasKey(contains("cooldown"))).thenReturn(true);

        assertThatThrownBy(() -> limiter.checkAndConsume(PHONE, IP))
            .isInstanceOf(OtpThrottledException.class);

        // A request refused by the cooldown must not burn hourly quota, or a retry loop would
        // lock the user out for the full hour.
        verify(valueOps, never()).increment(anyString());
    }

    @Test
    void rejectsOnceThePerPhoneHourlyQuotaIsExceeded() {
        when(valueOps.increment(contains("quota:phone"))).thenReturn(6L);

        assertThatThrownBy(() -> limiter.checkAndConsume(PHONE, IP))
            .isInstanceOf(OtpThrottledException.class);
    }

    @Test
    void rejectsOnceThePerIpHourlyQuotaIsExceeded() {
        when(valueOps.increment(contains("quota:ip"))).thenReturn(21L);

        assertThatThrownBy(() -> limiter.checkAndConsume(PHONE, IP))
            .isInstanceOf(OtpThrottledException.class);
    }

    /** The backstop on the SMS bill when the other layers are evaded by a distributed source. */
    @Test
    void rejectsOnceTheGlobalDailyBreakerTrips() {
        when(valueOps.increment(contains("quota:global"))).thenReturn(2001L);

        assertThatThrownBy(() -> limiter.checkAndConsume(PHONE, IP))
            .isInstanceOf(OtpThrottledException.class);
    }

    /**
     * With Redis down there is no working limiter, and this endpoint spends money per call.
     * Failing closed costs logins; failing open costs the SMS balance.
     */
    @Test
    void failsClosedWhenRedisIsUnreachable() {
        when(valueOps.increment(anyString())).thenReturn(null);

        assertThatThrownBy(() -> limiter.checkAndConsume(PHONE, IP))
            .isInstanceOf(OtpThrottledException.class);
    }

    @Test
    void setsTheWindowTtlOnlyOnTheFirstRequestInThatWindow() {
        when(valueOps.increment(contains("quota:phone"))).thenReturn(1L);
        when(valueOps.increment(contains("quota:ip"))).thenReturn(3L);

        limiter.checkAndConsume(PHONE, IP);

        // Re-expiring on every hit would slide the window forward indefinitely, so a steady
        // trickle of requests would never reset the quota.
        verify(redis).expire(contains("quota:phone"), eq(Duration.ofHours(1)));
        verify(redis, never()).expire(contains("quota:ip"), any(Duration.class));
    }

    @Test
    void skipsTheIpLayerWhenTheSourceAddressIsUnknown() {
        limiter.checkAndConsume(PHONE, null);

        verify(valueOps, never()).increment(contains("quota:ip"));
        verify(valueOps).increment(contains("quota:phone"));
    }
}
