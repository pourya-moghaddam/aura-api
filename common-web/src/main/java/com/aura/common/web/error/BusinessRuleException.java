package com.aura.common.web.error;

import org.springframework.http.HttpStatus;

/**
 * The request was well-formed but violates a domain rule — assigning a product to a non-leaf
 * category, ordering more units than are in stock.
 *
 * <p>422 rather than 400: the syntax was fine, the semantics were not.
 */
public class BusinessRuleException extends ApplicationException {

    public BusinessRuleException(String errorCode, String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, errorCode, message);
    }
}
