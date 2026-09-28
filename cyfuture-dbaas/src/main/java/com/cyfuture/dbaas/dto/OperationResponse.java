package com.cyfuture.dbaas.dto;

import com.cyfuture.dbaas.model.OperationStatus;
import com.cyfuture.dbaas.model.OperationType;
import com.cyfuture.dbaas.model.ProvisioningStage;
import lombok.Builder;

import java.time.Instant;

@Builder
public record OperationResponse(
        String operationId,
        OperationType type,
        OperationStatus status,
        ProvisioningStage stage,
        int progress,
        String message,
        String componentName,
        Integer targetReplicas,
        String targetStorageSize,
        String volumeName,
        String cpuRequest,
        String memoryRequest,
        String cpuLimit,
        String memoryLimit,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt
) {
    public OperationResponse(String operationId, OperationType type, OperationStatus status,
                             ProvisioningStage stage, int progress, String message,
                             Instant createdAt, Instant startedAt, Instant completedAt) {
        this(operationId, type, status, stage, progress, message,
                null, null, null, null, null, null, null, null,
                createdAt, startedAt, completedAt);
    }
}
