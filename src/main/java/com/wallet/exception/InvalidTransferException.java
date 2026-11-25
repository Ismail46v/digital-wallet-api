package com.wallet.exception;

import org.springframework.http.HttpStatus;

/** 400: the transfer request is invalid (self-transfer, invalid amount, ...). */
public class InvalidTransferException extends ApiException {

    public InvalidTransferException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
