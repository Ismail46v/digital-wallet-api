package com.wallet.exception;

import org.springframework.http.HttpStatus;

/** 422: the source wallet balance is lower than the requested amount. */
public class InsufficientFundsException extends ApiException {

    public InsufficientFundsException(Long walletId) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "Insufficient funds in wallet %d".formatted(walletId));
    }
}
