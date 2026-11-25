package com.wallet.exception;

import org.springframework.http.HttpStatus;

/**
 * Base class for all business exceptions. It is a {@link RuntimeException}, so {@code @Transactional}
 * rolls back automatically.
 * <p>
 * {@link GlobalExceptionHandler} maps every subclass to an {@link ErrorResponse} with {@link #getStatus()}.
 * A new business exception only needs to extend this class; the handler does not change.
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;

    protected ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    protected ApiException(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
