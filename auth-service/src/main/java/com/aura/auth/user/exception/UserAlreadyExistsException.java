package com.aura.auth.user.exception;

import com.aura.common.web.error.ConflictException;

public class UserAlreadyExistsException extends ConflictException {

    public UserAlreadyExistsException(String message) {
        super("user-already-exists", message);
    }
}
