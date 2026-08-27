package com.cyfuture.dbaas.dto;

import com.cyfuture.dbaas.model.DatabaseStatus;

public record DeleteDatabaseResponse(
        String databaseId,
        DatabaseStatus status,
        String message
) {
}
