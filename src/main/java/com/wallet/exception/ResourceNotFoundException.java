package com.wallet.exception;

import org.springframework.http.HttpStatus;

/** 404: the requested entity does not exist. */
public class ResourceNotFoundException extends ApiException {

    public ResourceNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, message);
    }

    public ResourceNotFoundException(String resource, Object id) {
        this("%s with id %s not found".formatted(resource, id));
    }
}
