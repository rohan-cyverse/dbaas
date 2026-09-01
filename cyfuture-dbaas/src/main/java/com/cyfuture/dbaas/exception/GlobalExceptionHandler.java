package com.cyfuture.dbaas.exception;

import com.cyfuture.dbaas.dto.ApiErrorResponse;
import com.cyfuture.dbaas.dto.FieldErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiErrorResponse> handleApiException(ApiException exception,
                                                        HttpServletRequest request) {
        return ResponseEntity.status(exception.getStatus()).body(error(
                exception.getStatus().value(), exception.getCode(),
                exception.isRetryable(), exception.getMessage(),
                List.of(), requestId(request)));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException exception,
                                                      HttpServletRequest request) {
        List<FieldErrorResponse> fieldErrors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldErrorResponse(error.getField(), error.getDefaultMessage()))
                .toList();
        return ResponseEntity.badRequest().body(error(
                400, "VALIDATION_FAILED", false, "Invalid request",
                fieldErrors, requestId(request)));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiErrorResponse> handleUnreadableBody(HttpMessageNotReadableException exception,
                                                          HttpServletRequest request) {
        return ResponseEntity.badRequest().body(error(400, "INVALID_REQUEST_BODY", false,
                "Invalid JSON or unsupported enum value. Check /api/v1/databases/options",
                List.of(), requestId(request)));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ApiErrorResponse> handleMissingHeader(MissingRequestHeaderException exception,
                                                         HttpServletRequest request) {
        return ResponseEntity.badRequest().body(error(400, "VALIDATION_FAILED", false,
                "Missing required header",
                List.of(new FieldErrorResponse(exception.getHeaderName(), "is required")),
                requestId(request)));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException exception,
                                                        HttpServletRequest request) {
        return ResponseEntity.badRequest().body(error(400, "VALIDATION_FAILED", false,
                "Invalid request parameter",
                List.of(new FieldErrorResponse(exception.getName(), "has an unsupported value")),
                requestId(request)));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception,
                                                     HttpServletRequest request) {
        return ResponseEntity.internalServerError().body(error(
                500, "INTERNAL_ERROR", true,
                "Unexpected internal error", List.of(), requestId(request)));
    }

    private ApiErrorResponse error(int status, String code,
                                   boolean retryable, String message,
                                   List<FieldErrorResponse> fieldErrors,
                                   String requestId) {
        String safeMessage = message == null || message.isBlank()
                ? "Request failed" : message;
        safeMessage = redact(safeMessage);
        return new ApiErrorResponse(code, safeMessage, fieldErrors, requestId,
                status, retryable, Instant.now());
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute("requestId");
        return value == null ? null : String.valueOf(value);
    }

    private String redact(String value) {
        return value.replaceAll("(?i)(password|passwd|pwd|token|secret)\\s*[:=]\\s*[^\\s,;\"']+",
                "$1=******");
    }
}
