package com.cyfuture.dbaas.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> handleApiException(ApiException exception) {
        return ResponseEntity.status(exception.getStatus()).body(error(
                exception.getStatus().value(), exception.getCode(),
                exception.isRetryable(), exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("Invalid request");
        return ResponseEntity.badRequest().body(error(
                400, "VALIDATION_FAILED", false, message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> handleUnreadableBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(error(400, "INVALID_REQUEST_BODY", false,
                "Invalid JSON or unsupported enum value. Check the project's /databases/options endpoint"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> handleUnexpected(Exception exception) {
        return ResponseEntity.internalServerError().body(error(
                500, "INTERNAL_ERROR", true,
                exception.getMessage() == null ? "Unexpected internal error" : exception.getMessage()));
    }

    private Map<String, Object> error(int status, String code,
                                      boolean retryable, String message) {
        String safeMessage = message == null || message.isBlank()
                ? "Request failed" : message;
        return Map.of("timestamp", Instant.now().toString(),
                "status", status, "code", code,
                "retryable", retryable, "message", safeMessage);
    }
}
