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
        DatabaseStatus status,
        ProvisioningStage stage,
        int progress,
        String statusUrl,
        String operationUrl,
        String message
) {}
