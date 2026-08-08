package com.aura.media.validation;

import com.aura.common.web.error.ApplicationException;
import org.springframework.http.HttpStatus;

/**
 * The uploaded bytes are not what was declared.
 *
 * <p>422 rather than 400: the request was well-formed, the file was not what it claimed to be.
 * The message deliberately does not say what the file <em>actually</em> is — that is free feedback
 * for someone iterating on a bypass.
 */
public class ContentValidationException extends ApplicationException {

    public ContentValidationException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "content-validation-failed", message);
    }
}
