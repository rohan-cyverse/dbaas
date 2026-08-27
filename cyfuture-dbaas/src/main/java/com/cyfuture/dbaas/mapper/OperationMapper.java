package com.cyfuture.dbaas.mapper;

import com.cyfuture.dbaas.dto.OperationResponse;
import com.cyfuture.dbaas.entity.OperationMetadata;
import org.springframework.stereotype.Component;

@Component
public class OperationMapper {
    public OperationResponse toResponse(OperationMetadata operation) {
        return OperationResponse.builder()
                .operationId(operation.getOperationId())
                .databaseId(operation.getDatabaseId())
                .project(operation.getProjectName())
                .type(operation.getType())
                .status(operation.getStatus())
                .stage(operation.getProvisioningStage())
                .progress(operation.getProgress())
                .message(operation.getMessage())
                .createdAt(operation.getCreatedAt())
                .startedAt(operation.getStartedAt())
                .completedAt(operation.getCompletedAt())
                .build();
    }
}
