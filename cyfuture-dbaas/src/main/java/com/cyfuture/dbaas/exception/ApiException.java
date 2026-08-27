package com.cyfuture.dbaas.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final boolean retryable;

    public ApiException(HttpStatus status, String message) {
        this(status, "REQUEST_FAILED", status.is5xxServerError(), message);
    }

    public ApiException(HttpStatus status, String code, boolean retryable, String message) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryable = retryable;
    }

}
