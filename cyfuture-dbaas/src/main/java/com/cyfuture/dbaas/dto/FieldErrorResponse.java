package com.cyfuture.dbaas.dto;

public record FieldErrorResponse(
        String field,
        String message
) {
}
