package com.aura.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * OTP issuance and verification limits.
 *
 * <p>These are not tuning knobs, they are the security boundary. {@code /otp/request} is
 * unauthenticated and each call spends money at the SMS provider, so without limits a loop drains
 * the account balance; and a six-digit code with unlimited guesses is brute-forced in seconds.
 *
 * <p>Message copy is deliberately absent: notification-service owns templates and selects them
 * from the event's purpose.
 *
 * @param ttl              how long a code stays valid
 * @param length           digits in the code
 * @param maxAttempts      wrong guesses before the code is burned. 5 attempts against 10^6
 *                         possibilities is a 1-in-200,000 chance per issued code.
 * @param resendCooldown   minimum gap between requests for the same phone
 * @param maxPerPhonePerHour ceiling per phone, so a cooldown cannot simply be waited out all day
 * @param maxPerIpPerHour  ceiling per source address, which is what actually stops enumeration of
 *                         many different numbers from one place
 * @param maxPerDayGlobal  circuit breaker on total daily sends. The last line of defence: if every
 *                         other limit is evaded by a distributed source, this caps the bill.
 * @param pepper           HMAC key for hashing codes at rest. Required under the prod profile.
 * @param devCode          <strong>development only.</strong> When set, this code is accepted in
 *                         place of the real one, so the login flow can be exercised locally — codes
 *                         are hashed at rest and deliberately never logged, so there is otherwise
 *                         no way to complete a sign-in without a working SMS provider. Refused
 *                         outright under the prod profile: it makes every account reachable by
 *                         anyone who knows a phone number.
 */
@ConfigurationProperties(prefix = "aura.auth.otp")
public record OtpProperties(
    @DefaultValue("3m") Duration ttl,
    @DefaultValue("6") int length,
    @DefaultValue("5") int maxAttempts,
    @DefaultValue("60s") Duration resendCooldown,
    @DefaultValue("5") int maxPerPhonePerHour,
    @DefaultValue("20") int maxPerIpPerHour,
    @DefaultValue("2000") int maxPerDayGlobal,
    @DefaultValue("") String pepper,
    @DefaultValue("otpRequestedOut-out-0") String otpBindingName,
    @DefaultValue("") String devCode
) {

    public boolean hasPepper() {
        return pepper != null && !pepper.isBlank();
    }

    public boolean hasDevCode() {
        return devCode != null && !devCode.isBlank();
    }
}
