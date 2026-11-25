package com.wallet.exception;

import org.springframework.http.HttpStatus;

/** 409: a request with the same {@code Idempotency-Key} has already been processed. */
public class DuplicateIdempotencyKeyException extends ApiException {

    public DuplicateIdempotencyKeyException(String idempotencyKey) {
        super(HttpStatus.CONFLICT, message(idempotencyKey));
    }

    public DuplicateIdempotencyKeyException(String idempotencyKey, Throwable cause) {
        super(HttpStatus.CONFLICT, message(idempotencyKey), cause);
    }

    private static String message(String idempotencyKey) {
        return "A request with Idempotency-Key '%s' has already been processed".formatted(idempotencyKey);
    }
}
