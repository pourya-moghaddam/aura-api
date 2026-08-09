package com.aura.auth.token;

import com.aura.common.web.error.ApplicationException;
import org.springframework.http.HttpStatus;

public class MissingRefreshTokenException extends ApplicationException {

    public MissingRefreshTokenException() {
        super(HttpStatus.UNAUTHORIZED, "missing-refresh-token", "No refresh session found.");
    }
}
