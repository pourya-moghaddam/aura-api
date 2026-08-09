package com.aura.auth.otp;

import com.aura.auth.config.OtpProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;

/**
 * Layered limits on OTP issuance.
 *
 * <p>Each layer stops a different attack, which is why one alone is not enough:
 * <ul>
 *   <li><b>Cooldown</b> — stops a user (or a retry loop) hammering resend on one number.</li>
 *   <li><b>Per phone / hour</b> — stops someone waiting out the cooldown all day to bombard one
 *       victim with texts.</li>
 *   <li><b>Per IP / hour</b> — stops enumeration: one source walking through many numbers, where
 *       every per-phone limit looks untouched.</li>
 *   <li><b>Global / day</b> — the backstop. If the first three are evaded by a distributed source,
 *       this is what caps the bill at the SMS provider.</li>
 * </ul>
 *
 * <p>All counters are Redis keys with a TTL, so the windows expire without a cleanup job.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OtpRateLimiter {

    private static final String COOLDOWN_KEY = "otp:cooldown:%s";
    private static final String PHONE_QUOTA_KEY = "otp:quota:phone:%s";
    private static final String IP_QUOTA_KEY = "otp:quota:ip:%s";
    private static final String GLOBAL_QUOTA_KEY = "otp:quota:global:%s";

    private static final Duration HOUR = Duration.ofHours(1);
    private static final Duration DAY = Duration.ofDays(1);

    /** Deliberately identical for every limit: which one tripped is not the caller's business. */
    private static final String MESSAGE = "Too many verification code requests. Please try again later.";

    private final StringRedisTemplate redis;
    private final OtpProperties otpProperties;

    /**
     * @param clientIp may be null when the source cannot be determined; the IP layer is then skipped
     * @throws OtpThrottledException if any limit is exceeded
     */
    public void checkAndConsume(String phone, String clientIp) {
        // Checked first and as a read, so a request rejected by a quota below does not also
        // restart the cooldown clock.
        if (Boolean.TRUE.equals(redis.hasKey(COOLDOWN_KEY.formatted(phone)))) {
            throw new OtpThrottledException(MESSAGE);
        }

        consume(PHONE_QUOTA_KEY.formatted(phone), otpProperties.maxPerPhonePerHour(), HOUR, "phone");

        if (clientIp != null && !clientIp.isBlank()) {
            consume(IP_QUOTA_KEY.formatted(clientIp), otpProperties.maxPerIpPerHour(), HOUR, "ip");
        }

        consume(GLOBAL_QUOTA_KEY.formatted(LocalDate.now()), otpProperties.maxPerDayGlobal(), DAY, "global");

        redis.opsForValue().set(
            COOLDOWN_KEY.formatted(phone), "1", otpProperties.resendCooldown());
    }

    private void consume(String key, int limit, Duration window, String layer) {
        Long count = redis.opsForValue().increment(key);
        if (count == null) {
            // Redis unreachable. Fail closed: an OTP endpoint with no working limiter is an open
            // tap on the SMS balance, and being unable to log in is the lesser harm.
            log.error("OTP rate limiter could not reach Redis; rejecting request");
            throw new OtpThrottledException(MESSAGE);
        }

        // Only the first increment in a window sets the TTL, so the window is a fixed hour/day
        // from the first request rather than sliding forward with every attempt.
        if (count == 1L) {
            redis.expire(key, window);
        }

        if (count > limit) {
            log.warn("OTP rate limit exceeded at the {} layer (count={}, limit={})", layer, count, limit);
            throw new OtpThrottledException(MESSAGE);
        }
    }
}
