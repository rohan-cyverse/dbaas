package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.entity.OperationMetadata;
import com.cyfuture.dbaas.model.OperationStatus;
import com.cyfuture.dbaas.model.OperationType;
import com.cyfuture.dbaas.model.ProvisioningStage;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import com.cyfuture.dbaas.repository.OperationMetadataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class KubeBlocksOperationReconciler {
    private final OperationMetadataRepository operationRepository;
    private final DatabaseMetadataRepository databaseRepository;
    private final KubeBlocksClient kubeBlocksClient;

    @Value("${dbaas.vertical-scaling-timeout-ms:900000}")
    private long verticalScalingTimeoutMs = 900_000L;

    @Scheduled(fixedDelayString = "${dbaas.operation-reconcile-ms:5000}")
    public void reconcile() {
        for (OperationMetadata operation : operationRepository.findByStatusIn(
                List.of(OperationStatus.PENDING, OperationStatus.RUNNING))) {
            if (operation.getType() == OperationType.CREATE
                    || operation.getType() == OperationType.DELETE) continue;
            reconcile(operation);
        }
    }

    void reconcile(OperationMetadata operation) {
        databaseRepository
                .findByDatabaseIdAndProjectName(operation.getDatabaseId(), operation.getProjectName())
                .ifPresent(database -> reconcile(database, operation));
    }

    private void reconcile(DatabaseMetadata database, OperationMetadata operation) {
        try {
            KubeBlocksClient.OpsRequestInfo live = kubeBlocksClient.getOpsRequest(
                    database.getNamespaceName(), operation.getOpsRequestName());
            operation.setMessage(safeMessage(live.message()));
            operation.setProgress(progress(live.progress(), status(live.phase())));
            if (live.startedAt() != null && (operation.getStartedAt() == null
                    || live.startedAt().isBefore(operation.getStartedAt()))) {
                operation.setStartedAt(live.startedAt());
            }

            OperationStatus status = status(live.phase());
            if (operation.getType() == OperationType.VERTICAL_SCALING) {
                status = reconcileVerticalScaling(database, operation, live, status);
            }
            operation.setStatus(status);
            operation.setProvisioningStage(status == OperationStatus.FAILED
                    ? ProvisioningStage.FAILED
                    : status == OperationStatus.SUCCEEDED
                    ? ProvisioningStage.READY
                    : ProvisioningStage.WAITING_FOR_REPLICAS);
            if (status == OperationStatus.SUCCEEDED || status == OperationStatus.FAILED) {
                operation.setProgress(100);
                operation.setCompletedAt(live.completedAt() == null ? Instant.now() : live.completedAt());
                if (status == OperationStatus.SUCCEEDED) syncDatabaseMetadata(database, operation);
            }
            operationRepository.save(operation);
        } catch (Exception exception) {
            if (operation.getType() == OperationType.VERTICAL_SCALING && timedOut(operation)) {
                failTimedOutVerticalOperation(operation,
                        "Could not verify VerticalScaling before timeout: " + safeMessage(exception.getMessage()));
            }
            log.debug("KubeBlocks operation reconciliation for {} will retry: {}",
                    operation.getOperationId(), exception.getMessage());
        }
    }

    private OperationStatus reconcileVerticalScaling(DatabaseMetadata database,
                                                     OperationMetadata operation,
                                                     KubeBlocksClient.OpsRequestInfo live,
                                                     OperationStatus reported) {
        if (instanceUpdateRestricted(live)) {
            operation.setMessage("KubeBlocks rejected VerticalScaling because InstanceUpdateRestricted: "
                    + safeMessage(live.message()));
            return OperationStatus.FAILED;
        }

        if (reported == OperationStatus.SUCCEEDED) {
            KubeBlocksClient.VerticalScalingObservation observation = kubeBlocksClient
                    .observeVerticalScaling(database.getNamespaceName(), database.getDatabaseId(),
                            operation.getComponentName(),
                            java.util.Map.of("cpu", operation.getCpuRequest(),
                                    "memory", operation.getMemoryRequest()),
                            java.util.Map.of("cpu", operation.getCpuLimit(),
                                    "memory", operation.getMemoryLimit()));
            operation.setMessage(safeMessage(observation.message()));
            if (observation.complete()) return OperationStatus.SUCCEEDED;
            if (timedOut(operation)) {
                operation.setMessage("VerticalScaling timed out after KubeBlocks completed but "
                        + "requested Pod resources were not observed: " + safeMessage(observation.message()));
                return OperationStatus.FAILED;
            }
            // The OpsRequest may say 100% before recreated Pods have picked up
            // their requested resources. Keep this visibly non-terminal.
            operation.setProgress(95);
            return OperationStatus.RUNNING;
        }

        if (timedOut(operation)) {
            operation.setMessage("VerticalScaling timed out waiting for KubeBlocks OpsRequest; last status: "
                    + safeMessage(live.message()));
            return OperationStatus.FAILED;
        }
        return reported;
    }

    private boolean instanceUpdateRestricted(KubeBlocksClient.OpsRequestInfo live) {
        return containsIgnoreCase(live.reason(), "InstanceUpdateRestricted")
                || containsIgnoreCase(live.message(), "InstanceUpdateRestricted");
    }

    private boolean containsIgnoreCase(String value, String expected) {
        return value != null && value.toLowerCase(java.util.Locale.ROOT)
                .contains(expected.toLowerCase(java.util.Locale.ROOT));
    }

    private boolean timedOut(OperationMetadata operation) {
        Instant started = operation.getStartedAt() == null ? operation.getCreatedAt() : operation.getStartedAt();
        return started != null && !started.plusMillis(verticalScalingTimeoutMs).isAfter(Instant.now());
    }

    private void failTimedOutVerticalOperation(OperationMetadata operation, String message) {
        operation.setStatus(OperationStatus.FAILED);
        operation.setProvisioningStage(ProvisioningStage.FAILED);
        operation.setProgress(100);
        operation.setMessage(message);
        operation.setCompletedAt(Instant.now());
        operationRepository.save(operation);
    }

    private void syncDatabaseMetadata(DatabaseMetadata database, OperationMetadata operation) {
        if (operation.getType() == OperationType.HORIZONTAL_SCALING
                && operation.getTargetReplicas() != null
                && primaryComponent(database, operation.getComponentName())) {
            database.setReplicas(operation.getTargetReplicas());
        }
        if (operation.getType() == OperationType.STORAGE_EXPANSION
                && operation.getTargetStorageSize() != null
                && primaryComponent(database, operation.getComponentName())) {
            database.setStorageGi(kubeBlocksClient.storageGi(operation.getTargetStorageSize()));
        }
        database.setMessage(operation.getMessage());
        database.setUpdatedAt(Instant.now());
        databaseRepository.save(database);
    }

    private boolean primaryComponent(DatabaseMetadata database, String componentName) {
        String configured = switch (database.getEngine()) {
            case POSTGRESQL -> "postgresql";
            case MYSQL -> "mysql";
            case MONGODB -> database.getMode() == com.cyfuture.dbaas.model.DatabaseMode.SHARDING
                    ? "shard" : "mongodb";
        };
        return configured.equals(componentName);
    }

    private OperationStatus status(String phase) {
        if ("Succeed".equalsIgnoreCase(phase)) return OperationStatus.SUCCEEDED;
        if ("Failed".equalsIgnoreCase(phase)
                || "Cancelled".equalsIgnoreCase(phase)
                || "Aborted".equalsIgnoreCase(phase)) {
            return OperationStatus.FAILED;
        }
        if ("Pending".equalsIgnoreCase(phase)) return OperationStatus.PENDING;
        return OperationStatus.RUNNING;
    }

    private int progress(String progress, OperationStatus status) {
        if (status == OperationStatus.SUCCEEDED || status == OperationStatus.FAILED) return 100;
        if (progress == null || !progress.contains("/")) return status == OperationStatus.PENDING ? 10 : 50;
        String[] parts = progress.split("/", 2);
        try {
            int done = Integer.parseInt(parts[0]);
            int total = Integer.parseInt(parts[1]);
            if (total <= 0) return 50;
            return Math.max(10, Math.min(95, (done * 100) / total));
        } catch (NumberFormatException exception) {
            return status == OperationStatus.PENDING ? 10 : 50;
        }
    }

    private String safeMessage(String message) {
        if (message == null || message.isBlank()) return "KubeBlocks is processing the operation";
        return message.replaceAll("(?i)(password|passwd|pwd|token|secret)\\s*[:=]\\s*[^\\s,;\"']+", "$1=******");
    }
}
