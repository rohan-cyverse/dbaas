package com.cyfuture.dbaas.dto;

import com.cyfuture.dbaas.model.OperationStatus;
import com.cyfuture.dbaas.model.OperationType;
import com.cyfuture.dbaas.model.ProvisioningStage;
import lombok.Builder;

import java.time.Instant;

@Builder
public record OperationResponse(
        String operationId,
        String databaseId,
        String project,
        OperationType type,
        OperationStatus status,
        boolean terminal,
        ProvisioningStage stage,
        int progress,
        String message,
        String failureReason,
        String statusUrl,
        int suggestedPollingIntervalSeconds,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt
) {}
