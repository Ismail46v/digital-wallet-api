package com.wallet.exception;

import com.wallet.entity.Currency;
import org.springframework.http.HttpStatus;

/** 400: the two wallets use different currencies. */
public class CurrencyMismatchException extends ApiException {

    public CurrencyMismatchException(Currency source, Currency target) {
        super(HttpStatus.BAD_REQUEST,
                "Currency mismatch: source wallet is %s, target wallet is %s".formatted(source, target));
    }
}
