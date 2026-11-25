package com.wallet.exception;

import com.wallet.entity.WalletStatus;
import org.springframework.http.HttpStatus;

/** 409: the operation targets a FROZEN or CLOSED wallet. */
public class WalletNotActiveException extends ApiException {

    public WalletNotActiveException(Long walletId, WalletStatus status) {
        super(HttpStatus.CONFLICT, "Wallet %d is %s".formatted(walletId, status));
    }
}
