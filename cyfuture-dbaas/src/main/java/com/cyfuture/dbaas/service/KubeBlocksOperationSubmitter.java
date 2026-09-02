package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.entity.OperationMetadata;
import com.cyfuture.dbaas.exception.ApiException;
import com.cyfuture.dbaas.model.OperationStatus;
import com.cyfuture.dbaas.model.OperationType;
import com.cyfuture.dbaas.model.ProvisioningStage;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import com.cyfuture.dbaas.repository.OperationMetadataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class KubeBlocksOperationSubmitter {
    private final KubeBlocksClient kubeBlocksClient;
    private final OperationMetadataRepository operationRepository;
    private final DatabaseMetadataRepository databaseRepository;

    @Async
    public void submit(String operationId) {
        OperationMetadata operation = operationRepository.findById(operationId).orElseThrow();
        DatabaseMetadata database = databaseRepository
                .findByDatabaseIdAndProjectName(operation.getDatabaseId(), operation.getProjectName())
                .orElseThrow();
        try {
            operation.setStatus(OperationStatus.RUNNING);
            operation.setProvisioningStage(ProvisioningStage.VALIDATING);
            operation.setProgress(10);
            operation.setMessage("Submitting KubeBlocks OpsRequest");
            if (operation.getStartedAt() == null) operation.setStartedAt(Instant.now());
            operation.setUpdatedAt(Instant.now());
            operationRepository.save(operation);

            switch (operation.getType()) {
                case VERTICAL_SCALING -> submitVertical(database, operation);
                case HORIZONTAL_SCALING -> submitHorizontal(database, operation);
                case STORAGE_EXPANSION -> submitStorage(database, operation);
                case RESTART -> kubeBlocksClient.createRestartOpsRequest(
                        database.getNamespaceName(), database.getDatabaseId(), operation.getOpsRequestName(),
                        restartComponents(database, operation));
                default -> throw new IllegalStateException("Unsupported KubeBlocks operation " + operation.getType());
            }

            operation.setProvisioningStage(ProvisioningStage.WAITING_FOR_REPLICAS);
            operation.setProgress(20);
            operation.setMessage("KubeBlocks OpsRequest accepted");
            operation.setUpdatedAt(Instant.now());
            operationRepository.save(operation);
        } catch (Exception exception) {
            operation.setStatus(OperationStatus.FAILED);
            operation.setProvisioningStage(ProvisioningStage.FAILED);
            operation.setProgress(100);
            operation.setMessage(safeMessage(exception));
            if (operation.getStartedAt() == null) operation.setStartedAt(Instant.now());
            operation.setCompletedAt(Instant.now());
            operation.setUpdatedAt(Instant.now());
            operationRepository.save(operation);
        }
    }

    private void submitVertical(DatabaseMetadata database, OperationMetadata operation) {
        kubeBlocksClient.ensureStrictInPlacePodUpdatePolicy(database.getNamespaceName(),
                database.getDatabaseId(), operation.getComponentName());
        kubeBlocksClient.createVerticalScalingOpsRequest(database.getNamespaceName(),
                database.getDatabaseId(), operation.getOpsRequestName(),
                operation.getComponentName(),
                Map.of("cpu", operation.getCpuRequest(), "memory", operation.getMemoryRequest()),
                Map.of("cpu", operation.getCpuLimit(), "memory", operation.getMemoryLimit()));
    }

    private void submitHorizontal(DatabaseMetadata database, OperationMetadata operation) {
        KubeBlocksClient.ClusterComponentInfo component = kubeBlocksClient.requireComponent(
                database.getNamespaceName(), database.getDatabaseId(), operation.getComponentName());
        kubeBlocksClient.createHorizontalScalingOpsRequest(database.getNamespaceName(),
                database.getDatabaseId(), operation.getOpsRequestName(), component.name(),
                component.replicas(), operation.getTargetReplicas());
    }

    private void submitStorage(DatabaseMetadata database, OperationMetadata operation) {
        KubeBlocksClient.ClusterComponentInfo component = kubeBlocksClient.requireComponent(
                database.getNamespaceName(), database.getDatabaseId(), operation.getComponentName());
        String currentStorage = component.storage(operation.getVolumeName());
        if (currentStorage == null) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Component " + component.name() + " has no volumeClaimTemplate named "
                            + operation.getVolumeName());
        }
        if (kubeBlocksClient.storageBytes(operation.getTargetStorageSize())
                <= kubeBlocksClient.storageBytes(currentStorage)) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "newStorageSize must be greater than the current size " + currentStorage);
        }
        kubeBlocksClient.createVolumeExpansionOpsRequest(database.getNamespaceName(),
                database.getDatabaseId(), operation.getOpsRequestName(), component.name(),
                operation.getVolumeName(), operation.getTargetStorageSize());
    }

    private List<String> restartComponents(DatabaseMetadata database, OperationMetadata operation) {
        if (operation.getComponentName() == null || operation.getComponentName().isBlank()) {
            return kubeBlocksClient.componentNames(database.getNamespaceName(), database.getDatabaseId());
        }
        return List.of(kubeBlocksClient.requireComponent(database.getNamespaceName(),
                database.getDatabaseId(), operation.getComponentName()).name());
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "KubeBlocks operation failed. Check application logs and Kubernetes events.";
        }
        return message.replaceAll("(?i)(password|passwd|pwd|token|secret)\\s*[:=]\\s*[^\\s,;\"']+", "$1=******");
    }
}
