package com.cyfuture.dbaas.dto;

import com.cyfuture.dbaas.model.RestoreStatus;

public record RestoreAcceptedResponse(
        String restoreId,
        String operationId,
        String databaseId,
        RestoreStatus status,
        String statusUrl,
        int pollAfterSeconds
) {}
