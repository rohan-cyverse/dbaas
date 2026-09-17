package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.dto.OperationResponse;
import com.cyfuture.dbaas.entity.OperationMetadata;
import com.cyfuture.dbaas.exception.ApiException;
import com.cyfuture.dbaas.mapper.OperationMapper;
import com.cyfuture.dbaas.model.OperationStatus;
import com.cyfuture.dbaas.model.ProvisioningStage;
import com.cyfuture.dbaas.repository.OperationMetadataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OperationService {
    private final OperationMetadataRepository operationRepository;
    private final OperationMapper operationMapper;
    private final ProjectService projectService;

    public OperationResponse get(String operationId) {
        var operation = operationRepository.findById(operationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Operation " + operationId + " was not found"));
        // Completed/failed backup and restore operations remain pollable while
        // a source database or project is deleting; its history remains available.
        projectService.requireExistingProject(operation.getProjectName());
        return operationMapper.toResponse(operation);
    }

    public OperationResponse get(String project, String operationId) {
        return operationMapper.toResponse(operationRepository
                .findByOperationIdAndProjectName(operationId, project)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Operation " + operationId + " was not found in project " + project)));
    }

    public OperationResponse getForDatabase(String project,
                                            String databaseId, String operationId) {
        return operationMapper.toResponse(operationRepository
                .findByOperationIdAndDatabaseIdAndProjectName(
                        operationId, databaseId, project)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Operation " + operationId + " was not found for database "
                                + databaseId + " in project " + project)));
    }

    public List<OperationResponse> listForDatabase(String project,
                                                   String databaseId) {
        return operationRepository
                .findByDatabaseIdAndProjectNameOrderByCreatedAtDesc(databaseId, project)
                .stream().map(operationMapper::toResponse).toList();
    }

    @Transactional
    public OperationResponse cancel(String project, String databaseId, String operationId) {
        OperationMetadata operation = operationRepository
                .findByOperationIdAndDatabaseIdAndProjectName(operationId, databaseId, project)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "OPERATION_NOT_FOUND", false,
                        "Operation " + operationId + " was not found for this database."));
        if (operation.getStatus() == OperationStatus.CANCELLED
                || operation.getStatus() == OperationStatus.SUCCEEDED
                || operation.getStatus() == OperationStatus.FAILED) {
            return operationMapper.toResponse(operation);
        }
        if (!cancellableStage(operation.getProvisioningStage())) {
            throw new ApiException(HttpStatus.CONFLICT, "OPERATION_NOT_CANCELLABLE", false,
                    "This operation can no longer be cancelled.");
        }
        operation.setStatus(OperationStatus.CANCEL_REQUESTED);
        operation.setMessage("Cancellation requested");
        operation.setLastHeartbeatAt(java.time.Instant.now());
        operationRepository.save(operation);
        return operationMapper.toResponse(operation);
    }

    public void rejectIfMutatingOperationActive(String project, String databaseId) {
        operationRepository.findByDatabaseIdAndProjectNameAndStatusIn(databaseId, project,
                        List.of(OperationStatus.PENDING, OperationStatus.RUNNING,
                                OperationStatus.CANCEL_REQUESTED, OperationStatus.CANCELLING))
                .stream().findFirst().ifPresent(operation -> {
                    throw new OperationConflictException(operation);
                });
    }

    public boolean cancellationRequested(String operationId) {
        return operationRepository.findById(operationId)
                .map(operation -> operation.getStatus() == OperationStatus.CANCEL_REQUESTED
                        || operation.getStatus() == OperationStatus.CANCELLING
                        || operation.getStatus() == OperationStatus.CANCELLED)
                .orElse(false);
    }

    public void markCancelled(String operationId) {
        operationRepository.findById(operationId).ifPresent(operation -> {
            operation.setStatus(OperationStatus.CANCELLED);
            operation.setProgress(100);
            operation.setMessage("Operation cancelled");
            operation.setLastHeartbeatAt(java.time.Instant.now());
            operation.setCompletedAt(java.time.Instant.now());
            operationRepository.save(operation);
        });
    }

    private boolean cancellableStage(ProvisioningStage stage) {
        return stage == ProvisioningStage.QUEUED
                || stage == ProvisioningStage.VALIDATING
                || stage == ProvisioningStage.CREATING_SAFETY_BACKUP
                || stage == ProvisioningStage.RESTORING_DATA
                || stage == ProvisioningStage.WAITING_FOR_REPLICAS
                || stage == ProvisioningStage.CREATING_CREDENTIALS
                || stage == ProvisioningStage.VERIFYING_CONNECTION;
    }

    public static class OperationConflictException extends ApiException {
        private final Map<String, Object> details;

        OperationConflictException(OperationMetadata operation) {
            super(HttpStatus.CONFLICT, "OPERATION_CONFLICT", true,
                    "Another mutating operation is already running for this database.");
            this.details = Map.of(
                    "operationId", operation.getOperationId(),
                    "type", operation.getType(),
                    "status", operation.getStatus(),
                    "stage", operation.getProvisioningStage(),
                    "createdAt", operation.getCreatedAt());
        }

        public Map<String, Object> details() {
            return details;
        }
    }
}
