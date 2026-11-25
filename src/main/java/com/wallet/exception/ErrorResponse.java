package com.wallet.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Map;

/**
 * Standard error body returned by every endpoint.
 * {@code fieldErrors} is present only for validation errors (field / parameter / header name to message).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        Map<String, String> fieldErrors) {

    public static ErrorResponse of(HttpStatusCode status, String message) {
        return of(status, message, null);
    }

    public static ErrorResponse of(HttpStatusCode status, String message, Map<String, String> fieldErrors) {
        HttpStatus resolved = HttpStatus.resolve(status.value());
        String error = resolved != null ? resolved.getReasonPhrase() : String.valueOf(status.value());
        return new ErrorResponse(Instant.now(), status.value(), error, message, fieldErrors);
    }
}
