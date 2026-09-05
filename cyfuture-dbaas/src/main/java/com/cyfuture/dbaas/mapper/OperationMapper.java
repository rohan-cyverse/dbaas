package com.cyfuture.dbaas.mapper;

import com.cyfuture.dbaas.dto.OperationResponse;
import com.cyfuture.dbaas.entity.OperationMetadata;
import com.cyfuture.dbaas.service.ClientMessages;
import org.springframework.stereotype.Component;

@Component
public class OperationMapper {
    public OperationResponse toResponse(OperationMetadata operation) {
        return OperationResponse.builder()
                .operationId(operation.getOperationId())
                .type(operation.getType())
                .status(operation.getStatus())
                .stage(operation.getProvisioningStage())
                .progress(operation.getProgress())
                .message(ClientMessages.operation(operation.getStatus()))
                .createdAt(operation.getCreatedAt())
                .startedAt(operation.getStartedAt())
                .completedAt(operation.getCompletedAt())
                .build();
    }
}
