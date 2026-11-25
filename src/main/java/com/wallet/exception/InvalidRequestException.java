package com.wallet.exception;

import org.springframework.http.HttpStatus;

/** 400: a generic invalid request that is not a transfer (unsupported sort property, invalid range, ...). */
public class InvalidRequestException extends ApiException {

    public InvalidRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
