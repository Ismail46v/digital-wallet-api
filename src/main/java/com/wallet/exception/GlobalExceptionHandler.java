package com.wallet.exception;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.MethodParameter;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The single global exception handler of the application. Every error becomes an {@link ErrorResponse}.
 * <p>
 * <b>Team note (Erdzhan):</b> extend THIS class with extra {@code @ExceptionHandler} methods when needed.
 * Do NOT create a second {@code @RestControllerAdvice}: two global advices compete during exception
 * resolution and the result depends on bean ordering.
 * New business exceptions usually need no handler at all: extend {@link ApiException} and pass the HTTP status.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String VALIDATION_FAILED = "Validation failed";

    // ------------------------------------------------------------------ business exceptions

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException ex) {
        return build(ex.getStatus(), ex.getMessage(), null);
    }

    // ------------------------------------------------------------------ validation (400)

    /** {@code @Valid @RequestBody} violations. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(fe -> errors.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        ex.getBindingResult().getGlobalErrors()
                .forEach(ge -> errors.putIfAbsent(ge.getObjectName(), ge.getDefaultMessage()));
        return build(HttpStatus.BAD_REQUEST, VALIDATION_FAILED, errors);
    }

    /**
     * Spring 6.1+ built-in method validation: constraint annotations directly on controller parameters
     * ({@code @RequestHeader}, {@code @RequestParam}, {@code @PathVariable}) and on {@code @Valid} bodies of the same method.
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleHandlerMethodValidation(HandlerMethodValidationException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            if (result instanceof ParameterErrors parameterErrors) {
                parameterErrors.getFieldErrors()
                        .forEach(fe -> errors.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
            } else {
                String name = parameterName(result.getMethodParameter());
                result.getResolvableErrors()
                        .forEach(error -> errors.putIfAbsent(name, error.getDefaultMessage()));
            }
        }
        return build(HttpStatus.BAD_REQUEST, VALIDATION_FAILED, errors);
    }

    /** Bean Validation through {@code @Validated} AOP proxies (services or controllers). */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            errors.putIfAbsent(lastNode(violation.getPropertyPath()), violation.getMessage());
        }
        return build(HttpStatus.BAD_REQUEST, VALIDATION_FAILED, errors);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException ex) {
        return build(HttpStatus.BAD_REQUEST, "Required header '%s' is missing".formatted(ex.getHeaderName()),
                Map.of(ex.getHeaderName(), "must not be blank"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String name = ex.getName();
        return build(HttpStatus.BAD_REQUEST, "Invalid value '%s' for parameter '%s'".formatted(ex.getValue(), name),
                Map.of(name, "invalid value"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException ex) {
        return build(HttpStatus.BAD_REQUEST, "Malformed or unreadable request body", null);
    }

    /** Sorting or filtering by a property that does not exist on the entity. */
    @ExceptionHandler(PropertyReferenceException.class)
    public ResponseEntity<ErrorResponse> handlePropertyReference(PropertyReferenceException ex) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), null);
    }

    // ------------------------------------------------------------------ persistence fallbacks (409)

    /**
     * Fallback for constraint violations that the services did not translate into a business exception
     * (e.g. a UNIQUE race). This prevents a 500 in that case.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return build(HttpStatus.CONFLICT, "The request conflicts with the current state of the data", null);
    }

    /** Lock timeouts, deadlocks or optimistic-lock failures. The client may retry. */
    @ExceptionHandler(ConcurrencyFailureException.class)
    public ResponseEntity<ErrorResponse> handleConcurrencyFailure(ConcurrencyFailureException ex) {
        log.warn("Concurrency failure: {}", ex.getMessage());
        return build(HttpStatus.CONFLICT, "Concurrent modification detected, please retry", null);
    }

    // ------------------------------------------------------------------ catch-all

    /**
     * Keeps the correct status for Spring MVC's own exceptions (405, 406, 415, 404 for unknown paths, ...),
     * which implement {@link org.springframework.web.ErrorResponse}. Anything else is an unexpected
     * server error: it is logged and its details are not leaked to the client.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        if (ex instanceof org.springframework.web.ErrorResponse springError) {
            String detail = springError.getBody().getDetail();
            return ResponseEntity.status(springError.getStatusCode())
                    .headers(springError.getHeaders())
                    .body(ErrorResponse.of(springError.getStatusCode(), detail != null ? detail : ex.getMessage()));
        }
        log.error("Unexpected error", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected internal error", null);
    }

    // ------------------------------------------------------------------ helpers

    private static ResponseEntity<ErrorResponse> build(HttpStatusCode status, String message,
                                                       Map<String, String> fieldErrors) {
        Map<String, String> errors = fieldErrors == null || fieldErrors.isEmpty() ? null : fieldErrors;
        return ResponseEntity.status(status).body(ErrorResponse.of(status, message, errors));
    }

    /** Uses the HTTP-visible name ({@code Idempotency-Key}, {@code page}, ...) instead of the Java parameter name. */
    private static String parameterName(MethodParameter parameter) {
        RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
        if (header != null) {
            return firstNonBlank(header.name(), header.value(), parameter);
        }
        RequestParam param = parameter.getParameterAnnotation(RequestParam.class);
        if (param != null) {
            return firstNonBlank(param.name(), param.value(), parameter);
        }
        PathVariable path = parameter.getParameterAnnotation(PathVariable.class);
        if (path != null) {
            return firstNonBlank(path.name(), path.value(), parameter);
        }
        return javaName(parameter);
    }

    private static String firstNonBlank(String name, String value, MethodParameter parameter) {
        if (!name.isBlank()) {
            return name;
        }
        return !value.isBlank() ? value : javaName(parameter);
    }

    private static String javaName(MethodParameter parameter) {
        String name = parameter.getParameterName();
        return name != null ? name : "arg" + parameter.getParameterIndex();
    }

    private static String lastNode(Path path) {
        String name = null;
        for (Path.Node node : path) {
            name = node.getName();
        }
        return name != null ? name : path.toString();
    }
}
