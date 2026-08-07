package com.aura.auth.otp;

import com.aura.auth.config.OtpProperties;
import com.aura.auth.user.exception.InvalidOtpException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Issues and verifies one-time codes.
 *
 * <p>Codes are stored as {@code HMAC-SHA256(code, pepper)}, never in plaintext. The window is only
 * a few minutes, but anyone who can read Redis — an operator, a backup, a misconfigured export —
 * could otherwise log in as any user who happens to be mid-login. The pepper lives in application
 * config rather than the datastore, so reading Redis alone is not enough.
 *
 * <p>Verification is capped and the code is burned on the last failure, which is what turns a
 * six-digit secret from trivially brute-forceable into a 1-in-200,000 gamble per issued code.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OtpService {

    private static final String CODE_KEY = "otp:code:%s";
    private static final String ATTEMPTS_KEY = "otp:attempts:%s";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** Identical for wrong, expired, and never-issued. Anything finer is a probing oracle. */
    private static final String INVALID_MESSAGE = "The code is incorrect or has expired.";

    private final StringRedisTemplate redis;
    private final OtpProperties otpProperties;
    private final OtpRateLimiter rateLimiter;
    private final Environment environment;
    private final SecureRandom secureRandom = new SecureRandom();

    private volatile byte[] pepper;

    /**
     * Applies every rate limit, then generates and stores a code.
     *
     * @return the plaintext code, to be handed to notification-service and never persisted
     */
    public String issue(String phone, String clientIp) {
        rateLimiter.checkAndConsume(phone, clientIp);

        String code = generateCode();

        redis.opsForValue().set(CODE_KEY.formatted(phone), hash(code), otpProperties.ttl());
        // Reset rather than leave stale: a fresh code deserves a fresh allowance, or a user who
        // fumbled the previous code would find the new one already half-burned.
        redis.delete(ATTEMPTS_KEY.formatted(phone));

        return code;
    }

    /**
     * @throws InvalidOtpException if the code is wrong, expired, or the attempt cap is reached
     */
    public void verify(String phone, String submittedCode) {
        String codeKey = CODE_KEY.formatted(phone);
        String storedHash = redis.opsForValue().get(codeKey);

        if (storedHash == null) {
            throw new InvalidOtpException(INVALID_MESSAGE);
        }

        String attemptsKey = ATTEMPTS_KEY.formatted(phone);
        Long attempts = redis.opsForValue().increment(attemptsKey);
        if (attempts != null && attempts == 1L) {
            // Expire alongside the code, so the counter cannot outlive what it is counting and
            // poison the next code issued to this number.
            redis.expire(attemptsKey, otpProperties.ttl());
        }

        if (attempts == null || attempts > otpProperties.maxAttempts()) {
            burn(phone);
            log.warn("OTP attempt cap reached; code burned");
            throw new InvalidOtpException(INVALID_MESSAGE);
        }

        // Constant-time: a timing-sensitive comparison leaks how many leading digits were right,
        // which reduces the search from 10^6 to about 60 guesses.
        if (!MessageDigest.isEqual(
            storedHash.getBytes(StandardCharsets.UTF_8),
            hash(submittedCode).getBytes(StandardCharsets.UTF_8))) {
            throw new InvalidOtpException(INVALID_MESSAGE);
        }

        // Success consumes the code; otherwise it stays replayable until its TTL.
        burn(phone);
    }

    private void burn(String phone) {
        redis.delete(CODE_KEY.formatted(phone));
        redis.delete(ATTEMPTS_KEY.formatted(phone));
    }

    private String generateCode() {
        int bound = (int) Math.pow(10, otpProperties.length());
        // Zero-padded: without it, one in ten codes would be short a digit and the user would be
        // told their correct code is wrong.
        return String.format("%0" + otpProperties.length() + "d", secureRandom.nextInt(bound));
    }

    private String hash(String code) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(pepper(), HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(code.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to hash OTP code", e);
        }
    }

    private byte[] pepper() {
        byte[] current = pepper;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (pepper == null) {
                pepper = resolvePepper();
            }
            return pepper;
        }
    }

    private byte[] resolvePepper() {
        if (otpProperties.hasPepper()) {
            return otpProperties.pepper().getBytes(StandardCharsets.UTF_8);
        }

        if (environment.matchesProfiles("prod")) {
            throw new IllegalStateException("""
                No OTP pepper configured. Set aura.auth.otp.pepper (AURA_AUTH_OTP_PEPPER). \
                Refusing to start under the prod profile with a generated one, which would \
                invalidate every in-flight code on restart and differ across instances.""");
        }

        log.warn("No OTP pepper configured - generating an ephemeral one. Codes issued before a "
            + "restart will not verify after it, and will not verify across instances.");
        byte[] generated = new byte[32];
        secureRandom.nextBytes(generated);
        return generated;
    }
}
