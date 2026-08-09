package com.aura.common.web.error;

import org.springframework.http.HttpStatus;

/**
 * A rate limit was hit.
 *
 * <p>429 rather than 403 so clients can back off and retry rather than treating it as permanent.
 *
 * <p>Subclasses should keep their messages vague about <em>which</em> limit tripped. Telling a
 * caller "this phone has had 5 codes this hour" versus "your IP has had 20" hands them a map for
 * working around it.
 */
public class RateLimitExceededException extends ApplicationException {

    public RateLimitExceededException(String errorCode, String message) {
        super(HttpStatus.TOO_MANY_REQUESTS, errorCode, message);
    }
}
