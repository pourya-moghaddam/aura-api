package com.aura.auth.user.exception;

import com.aura.common.web.error.ApplicationException;
import org.springframework.http.HttpStatus;

public class InvalidCredentialsException extends ApplicationException {

    public InvalidCredentialsException(String message) {
        super(HttpStatus.UNAUTHORIZED, "invalid-credentials", message);
    }
}
