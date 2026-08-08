package com.aura.media.upload;

import com.aura.media.config.UploadPolicyProperties;
import com.aura.common.web.error.RateLimitExceededException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Caps how many upload URLs one user can obtain per hour — rule 7.
 *
 * <p>Counted at issuance rather than at upload, because issuance is the only point this service is
 * still in the loop. A user holding sixty URLs can upload sixty files whatever happens afterwards,
 * so the limit has to bound how many they can be handed.
 */
@Component
@RequiredArgsConstructor
public class UploadRateLimiter {

    private static final String QUOTA_KEY = "media:quota:user:%d";
    private static final Duration WINDOW = Duration.ofHours(1);

    private final StringRedisTemplate redisTemplate;
    private final UploadPolicyProperties uploadPolicy;

    public void checkAndConsume(long userId) {
        String key = QUOTA_KEY.formatted(userId);
        Long used = redisTemplate.opsForValue().increment(key);

        if (used != null && used == 1L) {
            // First hit in this window starts the clock. Without the expiry the counter would be
            // permanent and the user would be locked out forever once they hit the cap.
            redisTemplate.expire(key, WINDOW);
        }

        if (used != null && used > uploadPolicy.uploadsPerHour()) {
            throw new RateLimitExceededException("upload-rate-limit",
                "Too many uploads. Try again later.");
        }
    }
}
