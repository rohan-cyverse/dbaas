package com.cyfuture.dbaas.dto;

import com.cyfuture.dbaas.model.DatabaseStatus;

public record DeleteDatabaseResponse(
        DatabaseStatus status,
        String message
) {
}
