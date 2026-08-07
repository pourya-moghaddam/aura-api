package com.aura.common.web.error;

import org.springframework.http.HttpStatus;

/**
 * The request conflicts with current state — a duplicate slug, a concurrent modification.
 */
public class ConflictException extends ApplicationException {

    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, "conflict", message);
    }

    public ConflictException(String errorCode, String message) {
        super(HttpStatus.CONFLICT, errorCode, message);
    }
}
