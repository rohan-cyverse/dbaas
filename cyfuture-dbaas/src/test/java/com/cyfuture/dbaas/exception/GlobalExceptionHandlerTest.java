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
}
