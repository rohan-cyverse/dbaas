package com.cyfuture.dbaas.exception;

import com.cyfuture.dbaas.dto.ApiErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class GlobalExceptionHandlerTest {
    @Test
    void providerDetailsAreNotReturnedToClients() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        var response = handler.handleApiException(new ApiException(HttpStatus.BAD_GATEWAY,
                "Kubernetes API returned pod db-123 in dbaas-p-prj-123: stack trace"));
        ApiErrorResponse body = response.getBody();

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertEquals("The service is temporarily unavailable. Please retry.", body.message());
        assertFalse(body.message().contains("Kubernetes"));
        assertFalse(body.message().contains("dbaas-p-"));
    }

    @Test
    void missingResourceReturnsNotFoundInsteadOfInternalError() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        var response = handler.handleResourceNotFound(new NoResourceFoundException(
                HttpMethod.GET, "/api/v1/reconciliation/orphans"));
        ApiErrorResponse body = response.getBody();

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("RESOURCE_NOT_FOUND", body.code());
        assertEquals("The requested resource was not found.", body.message());
        assertFalse(body.retryable());
    }

    @Test
    void deletionProtectionErrorExplainsHowToProceed() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        var response = handler.handleApiException(new ApiException(HttpStatus.CONFLICT,
                "DELETION_PROTECTION_ENABLED", false, "internal detail"));
        ApiErrorResponse body = response.getBody();

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("DELETION_PROTECTION_ENABLED", body.code());
        assertEquals("Deletion protection is enabled for this database. Disable it before deleting.",
                body.message());
        assertFalse(body.retryable());
    }

    @Test
    void componentRestartRequestsAreRejectedWithClearGuidance() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        var response = handler.handleApiException(new ApiException(HttpStatus.BAD_REQUEST,
                "RESTART_REQUEST_BODY_NOT_ALLOWED", false, "internal detail"));
        ApiErrorResponse body = response.getBody();

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("RESTART_REQUEST_BODY_NOT_ALLOWED", body.code());
        assertEquals("Restart requests do not accept a request body. Retry without a body to restart the full database.",
                body.message());
        assertFalse(body.retryable());
    }
}
