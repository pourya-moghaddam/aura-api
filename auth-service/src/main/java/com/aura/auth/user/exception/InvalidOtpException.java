package com.aura.auth.user.exception;

import com.aura.common.web.error.ApplicationException;
import org.springframework.http.HttpStatus;

public class InvalidOtpException extends ApplicationException {

    public InvalidOtpException(String message) {
        super(HttpStatus.UNAUTHORIZED, "invalid-otp", message);
    }
}
