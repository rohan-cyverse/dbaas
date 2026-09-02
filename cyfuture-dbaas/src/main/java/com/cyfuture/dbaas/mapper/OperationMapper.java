package com.cyfuture.dbaas.mapper;

import com.cyfuture.dbaas.dto.OperationResponse;
import com.cyfuture.dbaas.entity.OperationMetadata;
import org.springframework.stereotype.Component;

@Component
public class OperationMapper {
    public static final int POLLING_INTERVAL_SECONDS = 5;

    public OperationResponse toResponse(OperationMetadata operation) {
        boolean terminal = operation.getStatus() == com.cyfuture.dbaas.model.OperationStatus.SUCCEEDED
                || operation.getStatus() == com.cyfuture.dbaas.model.OperationStatus.FAILED;
        return OperationResponse.builder()
                .operationId(operation.getOperationId())
                .databaseId(operation.getDatabaseId())
                .project(operation.getProjectName())
                .type(operation.getType())
                .status(operation.getStatus())
                .terminal(terminal)
                .stage(operation.getProvisioningStage())
                .progress(operation.getProgress())
                .message(operation.getMessage())
                .failureReason(operation.getStatus() == com.cyfuture.dbaas.model.OperationStatus.FAILED
                        ? operation.getMessage() : null)
                .retryable(!terminal || operation.getStatus() == com.cyfuture.dbaas.model.OperationStatus.FAILED)
                .statusUrl(statusUrl(operation))
                .suggestedPollingIntervalSeconds(POLLING_INTERVAL_SECONDS)
                .createdAt(operation.getCreatedAt())
                .startedAt(operation.getStartedAt())
                .completedAt(operation.getCompletedAt())
                .updatedAt(operation.getUpdatedAt() == null ? operation.getCreatedAt() : operation.getUpdatedAt())
                .build();
    }

    private String statusUrl(OperationMetadata operation) {
        if (operation.getDatabaseId() != null
                && operation.getDatabaseId().startsWith("__project__")) {
            return "/api/v1/projects/" + operation.getProjectName()
                    + "/operations/" + operation.getOperationId();
        }
        return "/api/v1/projects/" + operation.getProjectName()
                + "/databases/" + operation.getDatabaseId()
                + "/operations/" + operation.getOperationId();
    }
}
