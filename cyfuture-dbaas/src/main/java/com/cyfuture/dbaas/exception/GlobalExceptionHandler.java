package com.cyfuture.dbaas.exception;

import com.cyfuture.dbaas.dto.ApiErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiErrorResponse> handleApiException(ApiException exception) {
        return ResponseEntity.status(exception.getStatus()).body(new ApiErrorResponse(
                exception.getCode(), messageFor(exception), exception.isRetryable()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        String field = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField())
                .orElse(null);
        String message = field == null ? "The request is invalid."
                : "Invalid value for " + field + ".";
        return ResponseEntity.badRequest().body(new ApiErrorResponse(
                "VALIDATION_FAILED", message, false));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiErrorResponse> handleUnreadableBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(new ApiErrorResponse(
                "INVALID_REQUEST_BODY", "Request body is invalid.", false));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiErrorResponse> handleResourceNotFound(NoResourceFoundException exception) {
        return ResponseEntity.status(404).body(new ApiErrorResponse(
                "RESOURCE_NOT_FOUND", "The requested resource was not found.", false));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception) {
        return ResponseEntity.internalServerError().body(new ApiErrorResponse(
                "INTERNAL_ERROR", "An unexpected error occurred. Please try again.", true));
    }

    private String messageFor(ApiException exception) {
        return switch (exception.getCode()) {
            case "DATABASE_NOT_READY" -> "Database is not ready.";
            case "PUBLIC_ENDPOINT_NOT_READY" -> "Public endpoint is not ready.";
            case "PROJECT_DELETION_IN_PROGRESS" -> "Project deletion is in progress.";
            case "VALIDATION_FAILED", "INVALID_REQUEST_BODY" -> "The request is invalid.";
            default -> switch (exception.getStatus().value()) {
                case 400 -> "The request is invalid.";
                case 401 -> "Authentication is required.";
                case 403 -> "You do not have permission to perform this action.";
                case 404 -> "The requested resource was not found.";
                case 409 -> "The request cannot be completed in the current state.";
                case 502, 503, 504 -> "The service is temporarily unavailable. Please retry.";
                default -> "The request could not be completed.";
            };
        };
    }
}
