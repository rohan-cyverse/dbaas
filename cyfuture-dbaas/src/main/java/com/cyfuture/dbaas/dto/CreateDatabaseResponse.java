package com.cyfuture.dbaas.dto;

import com.cyfuture.dbaas.model.DatabaseStatus;
import com.cyfuture.dbaas.model.ProvisioningStage;

public record CreateDatabaseResponse(
        String databaseId,
        String name,
        String operationId,
        DatabaseStatus status,
        ProvisioningStage stage,
        int progress,
        String message
) {}
