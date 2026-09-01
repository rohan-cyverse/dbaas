package com.cyfuture.dbaas.dto;

import com.cyfuture.dbaas.model.OperationStatus;
import com.cyfuture.dbaas.model.OperationType;
import com.cyfuture.dbaas.model.ProvisioningStage;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.time.Instant;

@Builder
public record OperationResponse(
        String operationId,
        String databaseId,
        @JsonIgnore @Schema(hidden = true)
        String project,
        OperationType type,
        OperationStatus status,
        boolean terminal,
        @JsonIgnore @Schema(hidden = true)
        ProvisioningStage stage,
        @JsonIgnore @Schema(hidden = true)
        int progress,
        String message,
        String failureReason,
        String statusUrl,
        int suggestedPollingIntervalSeconds,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt
) {}
