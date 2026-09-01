package com.cyfuture.dbaas.dto;

import com.cyfuture.dbaas.model.DatabaseEngine;
import com.cyfuture.dbaas.model.DatabaseStatus;
import com.cyfuture.dbaas.model.ProvisioningStage;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;

public record CreateDatabaseResponse(
        String databaseId,
        String operationId,
        @JsonIgnore @Schema(hidden = true)
        String project,
        @JsonIgnore @Schema(hidden = true)
        String namespace,
        @JsonIgnore @Schema(hidden = true)
        String name,
        @JsonIgnore @Schema(hidden = true)
        DatabaseEngine engine,
        @JsonIgnore @Schema(hidden = true)
        DatabaseStatus databaseStatus,
        com.cyfuture.dbaas.model.OperationStatus status,
        @JsonIgnore @Schema(hidden = true)
        ProvisioningStage stage,
        @JsonIgnore @Schema(hidden = true)
        int progress,
        String statusUrl,
        @JsonIgnore @Schema(hidden = true)
        String databaseStatusUrl,
        @JsonIgnore @Schema(hidden = true)
        String operationUrl,
        int suggestedPollingIntervalSeconds,
        String message
) {}
