package com.aura.common.web.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Base for exceptions that map to a deliberate HTTP response.
 *
 * <p>Anything extending this is a known, expected outcome — "that category does not exist", "that
 * code is already redeemed" — and is rendered as a problem+json body with a stable {@code type}
 * URI. Anything <em>not</em> extending this is a bug, and is rendered as a bare 500 with the detail
 * withheld from the client and logged at error level instead.
 */
@Getter
public abstract class ApplicationException extends RuntimeException {

    private final HttpStatus status;

    /**
     * Stable, machine-readable slug identifying the failure, e.g. {@code resource-not-found}.
     * Clients branch on this. It is part of the API contract — renaming one is a breaking change.
     */
    private final String errorCode;

    protected ApplicationException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    protected ApplicationException(HttpStatus status, String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.errorCode = errorCode;
    }
}
