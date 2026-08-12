package com.aura.media.upload;

import com.aura.common.web.error.RateLimitExceededException;
import com.aura.media.config.UploadPolicyProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UploadRateLimiterTest {

    private static final long USER = 42L;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @Mock
    private UploadPolicyProperties uploadPolicy;

    private UploadRateLimiter limiter;

    private void counterReturns(long value) {
        limiter = new UploadRateLimiter(redisTemplate, uploadPolicy);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(value);
    }

    @Test
    @DisplayName("the first request in a window sets the expiry that eventually frees the user")
    void firstRequestSetsExpiry() {
        // Without this the counter never resets and the user is locked out permanently once they
        // reach the cap - a bug that only appears an hour after anyone tests it.
        counterReturns(1L);
        when(uploadPolicy.uploadsPerHour()).thenReturn(20);

        limiter.checkAndConsume(USER);

        verify(redisTemplate).expire("media:quota:user:42", Duration.ofHours(1));
    }

    @Test
    @DisplayName("later requests in the same window do not extend the expiry")
    void laterRequestsDoNotResetTheWindow() {
        // Refreshing the TTL on every call would make a steady trickle of uploads keep the window
        // alive forever, so the user would never get their allowance back.
        counterReturns(5L);
        when(uploadPolicy.uploadsPerHour()).thenReturn(20);

        limiter.checkAndConsume(USER);

        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("a request exactly on the cap is allowed")
    void atTheCapAllowed() {
        counterReturns(20L);
        when(uploadPolicy.uploadsPerHour()).thenReturn(20);

        assertThatCode(() -> limiter.checkAndConsume(USER)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("one past the cap is a 429")
    void overTheCapRefused() {
        counterReturns(21L);
        when(uploadPolicy.uploadsPerHour()).thenReturn(20);

        assertThatThrownBy(() -> limiter.checkAndConsume(USER))
            .isInstanceOf(RateLimitExceededException.class)
            .hasMessageContaining("Too many uploads");
    }
}
