package com.cyfuture.dbaas.dto;

/** Stable, client-safe error shape. HTTP status is carried by the response. */
public record ApiErrorResponse(
        String code,
        String message,
        boolean retryable
) {}
