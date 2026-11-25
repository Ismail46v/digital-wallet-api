package com.wallet.exception;

import org.springframework.http.HttpStatus;

/** 409: a unique business value already exists (tag/category name, user email, ...). */
public class DuplicateResourceException extends ApiException {

    public DuplicateResourceException(String message) {
        super(HttpStatus.CONFLICT, message);
    }

    public DuplicateResourceException(String message, Throwable cause) {
        super(HttpStatus.CONFLICT, message, cause);
    }
}
