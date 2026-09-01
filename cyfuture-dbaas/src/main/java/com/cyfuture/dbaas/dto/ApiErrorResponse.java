package com.cyfuture.dbaas.dto;

import java.time.Instant;
import java.util.List;

public record ApiErrorResponse(
        String code,
        String message,
        List<FieldErrorResponse> fieldErrors,
        String requestId,
        int status,
        boolean retryable,
        Instant timestamp
) {
}
