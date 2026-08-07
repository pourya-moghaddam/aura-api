package com.aura.auth.user.exception;

import com.aura.common.web.error.ApplicationException;
import org.springframework.http.HttpStatus;

public class UnknownRoleException extends ApplicationException {

    public UnknownRoleException(String roleName) {
        super(HttpStatus.BAD_REQUEST, "unknown-role", "No such role: " + roleName);
    }
}
