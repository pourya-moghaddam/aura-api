package com.aura.auth.otp;

import com.aura.common.web.error.RateLimitExceededException;

/**
 * A rate limit was hit. 429 so clients can back off intelligently rather than retrying blindly.
 *
 * <p>The message never distinguishes <em>which</em> limit tripped. Telling a caller "this phone has
 * had 5 codes this hour" versus "your IP has had 20" hands them a map for working around it.
 */
public class OtpThrottledException extends RateLimitExceededException {

    public OtpThrottledException(String message) {
        super("otp-throttled", message);
    }
}
