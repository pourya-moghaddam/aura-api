package com.aura.common.phone;

import com.aura.common.web.error.ApplicationException;
import org.springframework.http.HttpStatus;

public class InvalidPhoneNumberException extends ApplicationException {

    public InvalidPhoneNumberException(String message) {
        super(HttpStatus.BAD_REQUEST, "invalid-phone-number", message);
    }
}
