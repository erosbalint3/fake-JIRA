package com.fakejira.common;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final java.util.Map<String, String> fieldErrors;

    public ApiException(HttpStatus status, String message) {
        this(status, message, java.util.Map.of());
    }

    public ApiException(HttpStatus status, String message, java.util.Map<String, String> fieldErrors) {
        super(message);
        this.status = status;
        this.fieldErrors = fieldErrors;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public java.util.Map<String, String> getFieldErrors() {
        return fieldErrors;
    }

    /** A 400 that highlights one form field. */
    public static ApiException field(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message, java.util.Map.of(field, message));
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, message);
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }
}
