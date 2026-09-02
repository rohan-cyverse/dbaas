package com.cyfuture.dbaas.dto;

import com.cyfuture.dbaas.model.DatabaseEngine;
import com.cyfuture.dbaas.model.DatabaseStatus;
import com.cyfuture.dbaas.model.ProvisioningStage;

public record CreateDatabaseResponse(
        String databaseId,
        String operationId,
        String project,
        String namespace,
        String name,
        DatabaseEngine engine,
        DatabaseStatus databaseStatus,
        com.cyfuture.dbaas.model.OperationStatus status,
        ProvisioningStage stage,
        int progress,
        String statusUrl,
        String databaseStatusUrl,
        String operationUrl,
        int suggestedPollingIntervalSeconds,
        String message
) {}
