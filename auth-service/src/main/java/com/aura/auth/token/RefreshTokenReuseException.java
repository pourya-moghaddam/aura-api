package com.aura.auth.token;

import com.aura.common.web.error.ApplicationException;
import org.springframework.http.HttpStatus;

/**
 * A refresh token that was already consumed was presented again.
 *
 * <p>On the legitimate rotation path a token is used exactly once. Seeing it a second time means
 * either a client retried after a lost response (recoverable, if rare) or the token was stolen and
 * both the thief and the rightful owner are now racing to use it (not recoverable). There is no way
 * to tell those apart from here, so the response is the same either way: the whole family is
 * revoked and the caller must sign in again.
 */
public class RefreshTokenReuseException extends ApplicationException {

    public RefreshTokenReuseException() {
        super(HttpStatus.UNAUTHORIZED, "refresh-token-reused",
            "This session is no longer valid. Please sign in again.");
    }
}
