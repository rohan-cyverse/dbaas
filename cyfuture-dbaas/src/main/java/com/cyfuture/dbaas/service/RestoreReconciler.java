package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.dto.PublicEndpointResponse;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.entity.RestoreRequestMetadata;
import com.cyfuture.dbaas.entity.BackupMetadata;
import com.cyfuture.dbaas.exception.ApiException;
import com.cyfuture.dbaas.model.DatabaseStatus;
import com.cyfuture.dbaas.model.OperationStatus;
import com.cyfuture.dbaas.model.ProvisioningStage;
import com.cyfuture.dbaas.model.RestoreAccessMode;
import com.cyfuture.dbaas.model.RestoreStatus;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import com.cyfuture.dbaas.repository.BackupMetadataRepository;
import com.cyfuture.dbaas.repository.OperationMetadataRepository;
import com.cyfuture.dbaas.repository.RestoreRequestMetadataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Completes a restore only after KubeBlocks, credentials, and the public route are all ready. */
@Service
@RequiredArgsConstructor
@Slf4j
public class RestoreReconciler {
    private final RestoreRequestMetadataRepository restoreRepository;
    private final DatabaseMetadataRepository databaseRepository;
    private final OperationMetadataRepository operationRepository;
    private final BackupMetadataRepository backupRepository;
    private final KubeBlocksClient kubeBlocksClient;
    private final CredentialLifecycleService credentialLifecycleService;
    private final SharedGatewayService sharedGatewayService;
    private final ProvisioningProgressService progressService;
    private final RestoreSubmissionService submissionService;
    private final OperationService operationService;

    @Value("${dbaas.restore-timeout-ms:3600000}")
    private long restoreTimeoutMs = 3_600_000L;
    @Value("${dbaas.restore.rollback-retention-minutes:60}")
    private long rollbackRetentionMinutes = 60L;

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() { reconcile(); }

    @Scheduled(fixedDelayString = "${dbaas.backup.reconcile-ms:5000}")
    public void reconcile() {
        for (RestoreRequestMetadata restore : restoreRepository.findByStatusInOrderByCreatedAtAsc(
                List.of(RestoreStatus.PENDING, RestoreStatus.SAFETY_BACKUP, RestoreStatus.RESTORING,
                        RestoreStatus.VALIDATING, RestoreStatus.CUTTING_OVER, RestoreStatus.ROLLING_BACK,
                        RestoreStatus.RUNNING))) {
            try {
                if (timedOut(restore)) {
                    fail(restore, "RESTORE_TIMEOUT", "Restore timed out before readiness was verified.");
                    continue;
                }
                if (restore.getStatus() == RestoreStatus.PENDING) {
                    refresh(restore);
                } else {
                    refresh(restore);
                }
            } catch (Exception exception) {
                if (timedOut(restore)) {
                    fail(restore, "RESTORE_TIMEOUT", "Restore timed out before readiness was verified.");
                } else if (exception instanceof ApiException apiException && !apiException.isRetryable()) {
                    fail(restore, BackupRestoreSafety.failureCode(apiException, "RESTORE_RECONCILE_FAILED"),
                            BackupRestoreSafety.safeMessage(apiException,
                                    "Restore validation failed before readiness was verified."));
                }
                log.debug("Restore reconciliation for {} will retry: {}", restore.getRestoreId(),
                        BackupRestoreSafety.safeMessage(exception, "Restore reconciliation will retry."));
            }
        }
    }

    /** Performs one live restore refresh and is used by restore GET endpoints. */
    public void refresh(RestoreRequestMetadata restore) {
        if (restore.getStatus() == RestoreStatus.PENDING || restore.getStatus() == RestoreStatus.SAFETY_BACKUP) {
            submissionService.submit(restore.getRestoreId());
            return;
        }
        reconcile(restore);
    }

    private void reconcile(RestoreRequestMetadata restore) {
        DatabaseMetadata target = databaseRepository.findByDatabaseIdAndProjectName(
                restore.getRestoredDatabaseId(), restore.getProjectName()).orElse(null);
        if (target == null) {
            fail(restore, "RESTORE_TARGET_METADATA_MISSING", "Restore target metadata is unavailable.");
            return;
        }
        if (operationService.cancellationRequested(restore.getOperationId())
                && restore.getStatus() != RestoreStatus.CUTTING_OVER) {
            cancel(restore, target);
            return;
        }
        KubeBlocksClient.OpsRequestInfo observed = kubeBlocksClient.getOpsRequest(
                target.getNamespaceName(), restore.getKubernetesOpsRequestName());
        if (failedPhase(observed.phase())) {
            fail(restore, errorCode(observed, "KUBERNETES_RESTORE_FAILED"), observed.message());
            return;
        }
        KubeBlocksClient.RestoreObservation restoreObserved = kubeBlocksClient.observeRestore(
                target.getNamespaceName(), restore.getKubernetesOpsRequestName(),
                restore.getKubernetesRestoreName());
        if (restoreObserved.exists()) {
            synchronizeRestoreResource(restore, restoreObserved);
            if ("Failed".equalsIgnoreCase(restoreObserved.phase())) {
                fail(restore, "KUBERNETES_RESTORE_FAILED", restoreObserved.message());
                return;
            }
            if (!"Completed".equalsIgnoreCase(restoreObserved.phase())) {
                updateRunning(restore, observed, RestoreStatus.RESTORING, 40,
                        "KubeBlocks is restoring database data");
                return;
            }
        } else if (!"Succeed".equalsIgnoreCase(observed.phase())) {
            updateRunning(restore, observed, RestoreStatus.RESTORING, 30,
                    "Waiting for the KubeBlocks Restore resource");
            return;
        }
        if (!"Succeed".equalsIgnoreCase(observed.phase())) {
            updateRunning(restore, observed, RestoreStatus.RESTORING, 45,
                    "KubeBlocks is restoring database data");
            return;
        }

        KubeBlocksClient.ClusterObservation cluster = kubeBlocksClient.observeCluster(
                target.getNamespaceName(), temporaryClusterName(restore));
        if (!cluster.exists()) {
            updateRunning(restore, observed, RestoreStatus.VALIDATING, 60,
                    "Waiting for the restored database Cluster");
            return;
        }
        if ("Failed".equalsIgnoreCase(cluster.phase())) {
            fail(restore, "KUBERNETES_RESTORED_CLUSTER_FAILED", cluster.message());
            return;
        }
        if (!cluster.healthy()) {
            updateRunning(restore, observed, RestoreStatus.VALIDATING, 70,
                    "Waiting for restored database replicas");
            return;
        }
        String restoredLogicalDatabase = CredentialLifecycleService.logicalDatabaseName(target);
        String restoredUsername = CredentialLifecycleService.managedUsername(restore.getSourceDatabaseId());
        if (!credentialLifecycleService.readyForRestoredCluster(target, temporaryClusterName(restore),
                restoredLogicalDatabase,
                restoredUsername)) {
            updateRunning(restore, observed, RestoreStatus.VALIDATING, 82,
                    "Validating restored database connection");
            return;
        }
        String actualLogicalDatabase = credentialLifecycleService.databaseName(target);
        if (!restoredLogicalDatabase.equals(actualLogicalDatabase)) {
            fail(restore, "RESTORED_DATABASE_NAME_MISMATCH",
                    "Restored database credentials do not target the restored logical database.");
            return;
        }
        if (restore.getStatus() != RestoreStatus.CUTTING_OVER) {
            restore.setStatus(RestoreStatus.CUTTING_OVER);
            restoreRepository.save(restore);
            target.setStatus(DatabaseStatus.MAINTENANCE);
            target.setProvisioningStage(ProvisioningStage.CUTTING_OVER);
            target.setProgress(90);
            target.setMessage("Switching database endpoint to restored cluster");
            target.setUpdatedAt(Instant.now());
            databaseRepository.save(target);
            updateOperation(restore, OperationStatus.RUNNING, ProvisioningStage.CUTTING_OVER, 90,
                    "Switching database endpoint to restored cluster", false);
            return;
        }

        target.setActiveClusterName(temporaryClusterName(restore));
        target.setStatus(DatabaseStatus.RUNNING);
        target.setProvisioningStage(ProvisioningStage.CONFIGURING_NETWORK);
        target.setProgress(95);
        target.setUpdatedAt(Instant.now());
        databaseRepository.save(target);
        PublicEndpointResponse endpoint = sharedGatewayService.configure(target);
        if (!endpoint.ready()) {
            updateOperation(restore, OperationStatus.RUNNING, ProvisioningStage.CONFIGURING_NETWORK,
                    95, "Waiting for the stable endpoint to target restored cluster", false);
            return;
        }
        progressService.ready(target);
        restore.setStatus(RestoreStatus.COMPLETED);
        restore.setTemporary(false);
        restore.setPromotedAt(Instant.now());
        restore.setOldClusterDeleteAt(Instant.now().plusSeconds(rollbackRetentionMinutes * 60));
        restore.setCompletedAt(Instant.now());
        restore.setLastObservedAt(Instant.now());
        restore.setFailureCode(null);
        restore.setFailureMessage(null);
        restoreRepository.save(restore);
        operationRepository.findById(restore.getOperationId()).ifPresent(operation -> {
            operation.setStatus(OperationStatus.SUCCEEDED);
            operation.setProvisioningStage(ProvisioningStage.READY);
            operation.setProgress(100);
            operation.setMessage("Restore completed and stable endpoint targets restored cluster");
            operation.setLastHeartbeatAt(Instant.now());
            if (operation.getStartedAt() == null) operation.setStartedAt(Instant.now());
            operation.setCompletedAt(Instant.now());
            operationRepository.save(operation);
        });
    }

    private void synchronizeRestoreResource(RestoreRequestMetadata restore,
                                            KubeBlocksClient.RestoreObservation observed) {
        boolean changed = !Objects.equals(restore.getKubernetesRestoreName(), observed.restoreName())
                || (observed.startedAt() != null && !Objects.equals(restore.getStartedAt(), observed.startedAt()));
        if (!changed) return;
        restore.setKubernetesRestoreName(observed.restoreName());
        if (observed.startedAt() != null) restore.setStartedAt(observed.startedAt());
        restore.setLastObservedAt(Instant.now());
        restoreRepository.save(restore);
    }

    private void updateRunning(RestoreRequestMetadata restore, KubeBlocksClient.OpsRequestInfo observed,
                               RestoreStatus restoreStatus, int fallbackProgress, String operationMessage) {
        int progress = progress(observed.progress(), fallbackProgress);
        boolean changed = restore.getStatus() != restoreStatus
                || !Objects.equals(restore.getStartedAt(), observed.startedAt());
        if (changed) {
            restore.setStatus(restoreStatus);
            if (observed.startedAt() != null) restore.setStartedAt(observed.startedAt());
            restore.setLastObservedAt(Instant.now());
            restoreRepository.save(restore);
        }
        operationRepository.findById(restore.getOperationId()).ifPresent(operation -> {
            if (operation.getStatus() == OperationStatus.RUNNING && operation.getProgress() == progress
                    && Objects.equals(operation.getMessage(), operationMessage)) return;
            operation.setStatus(OperationStatus.RUNNING);
            operation.setProvisioningStage(ProvisioningStage.RESTORING_DATA);
            operation.setProgress(progress);
            operation.setMessage(operationMessage);
            operation.setLastHeartbeatAt(Instant.now());
            if (operation.getStartedAt() == null) operation.setStartedAt(Instant.now());
            operationRepository.save(operation);
        });
    }

    private void fail(RestoreRequestMetadata restore, String code, String message) {
        if (restore.getStatus() == RestoreStatus.CUTTING_OVER && !restore.isRollbackAttempted()
                && restore.getOldClusterName() != null && !restore.getOldClusterName().isBlank()) {
            restore.setRollbackAttempted(true);
            restore.setStatus(RestoreStatus.ROLLING_BACK);
            restore.setFailureCode(code);
            restore.setFailureMessage(BackupRestoreSafety.safeMessage(null, message));
            restore.setLastObservedAt(Instant.now());
            restoreRepository.save(restore);
            DatabaseMetadata target = databaseRepository.findByDatabaseIdAndProjectName(
                    restore.getRestoredDatabaseId(), restore.getProjectName()).orElse(null);
            if (target != null) {
                target.setActiveClusterName(restore.getOldClusterName());
                target.setStatus(DatabaseStatus.RUNNING);
                target.setProvisioningStage(ProvisioningStage.READY);
                target.setProgress(100);
                target.setMessage("Restore cutover rolled back");
                target.setUpdatedAt(Instant.now());
                databaseRepository.save(target);
                cleanupTemporaryCluster(restore, target);
            }
            restore.setStatus(RestoreStatus.FAILED);
            restore.setCompletedAt(Instant.now());
            restoreRepository.save(restore);
            operationRepository.findById(restore.getOperationId()).ifPresent(operation -> {
                operation.setStatus(OperationStatus.FAILED);
                operation.setProvisioningStage(ProvisioningStage.FAILED);
                operation.setProgress(100);
                operation.setMessage("Restore cutover failed; rolled back to the previous cluster");
                operation.setLastHeartbeatAt(Instant.now());
                operation.setCompletedAt(Instant.now());
                operationRepository.save(operation);
            });
            return;
        }
        restore.setStatus(RestoreStatus.FAILED);
        restore.setFailureCode(code);
        restore.setFailureMessage(BackupRestoreSafety.safeMessage(null, message));
        restore.setCompletedAt(Instant.now());
        restore.setLastObservedAt(Instant.now());
        restoreRepository.save(restore);
        databaseRepository.findByDatabaseIdAndProjectName(restore.getRestoredDatabaseId(), restore.getProjectName())
                .ifPresent(target -> {
                    cleanupTemporaryCluster(restore, target);
                    target.setStatus(DatabaseStatus.RUNNING);
                    target.setProvisioningStage(ProvisioningStage.READY);
                    target.setProgress(100);
                    target.setMessage("Restore failed; original database remains active.");
                    target.setUpdatedAt(Instant.now());
                    databaseRepository.save(target);
                });
        operationRepository.findById(restore.getOperationId()).ifPresent(operation -> {
            operation.setStatus(OperationStatus.FAILED);
            operation.setProvisioningStage(ProvisioningStage.FAILED);
            operation.setProgress(100);
            operation.setMessage("Restore failed.");
            operation.setLastHeartbeatAt(Instant.now());
            operation.setCompletedAt(Instant.now());
            operationRepository.save(operation);
        });
    }

    private void cancel(RestoreRequestMetadata restore, DatabaseMetadata target) {
        cleanupTemporaryCluster(restore, target);
        restore.setStatus(RestoreStatus.CANCELLED);
        restore.setFailureCode("RESTORE_CANCELLED");
        restore.setFailureMessage("Restore was cancelled before cutover; the original database was left untouched.");
        restore.setCompletedAt(Instant.now());
        restore.setLastObservedAt(Instant.now());
        restoreRepository.save(restore);
        target.setStatus(DatabaseStatus.RUNNING);
        target.setProvisioningStage(ProvisioningStage.READY);
        target.setProgress(100);
        target.setMessage("Restore cancelled");
        target.setUpdatedAt(Instant.now());
        databaseRepository.save(target);
        operationService.markCancelled(restore.getOperationId());
    }

    private void updateOperation(RestoreRequestMetadata restore, OperationStatus status,
                                 ProvisioningStage stage, int progress, String message,
                                 boolean completed) {
        operationRepository.findById(restore.getOperationId()).ifPresent(operation -> {
            operation.setStatus(status);
            operation.setProvisioningStage(stage);
            operation.setProgress(progress);
            operation.setMessage(message);
            operation.setLastHeartbeatAt(Instant.now());
            if (operation.getStartedAt() == null) operation.setStartedAt(Instant.now());
            if (completed) operation.setCompletedAt(Instant.now());
            operationRepository.save(operation);
        });
    }

    private void cleanupTemporaryCluster(RestoreRequestMetadata restore, DatabaseMetadata database) {
        String temporaryCluster = temporaryClusterName(restore);
        if (temporaryCluster.equals(database.physicalClusterName())) return;
        try {
            kubeBlocksClient.requestDelete(database.getNamespaceName(), temporaryCluster);
        } catch (Exception ignored) {
            // Cleanup is retried by reconciliation.
        }
    }

    private String temporaryClusterName(RestoreRequestMetadata restore) {
        return restore.getTemporaryClusterName() == null || restore.getTemporaryClusterName().isBlank()
                ? restore.getRestoredDatabaseId() + "-restore-" + restore.getRestoreId().substring(4, 12)
                : restore.getTemporaryClusterName();
    }

    private String errorCode(KubeBlocksClient.OpsRequestInfo observed, String fallback) {
        return observed.reason() == null || observed.reason().isBlank()
                ? fallback : observed.reason();
    }

    private boolean failedPhase(String phase) {
        return "Failed".equalsIgnoreCase(phase) || "Cancelled".equalsIgnoreCase(phase)
                || "Aborted".equalsIgnoreCase(phase);
    }

    private int progress(String value, int fallback) {
        if (value == null || !value.contains("/")) return fallback;
        String[] values = value.split("/", 2);
        try {
            int done = Integer.parseInt(values[0]);
            int total = Integer.parseInt(values[1]);
            return total <= 0 ? fallback : Math.max(20, Math.min(95, done * 100 / total));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private boolean timedOut(RestoreRequestMetadata restore) {
        Instant started = restore.getStartedAt() == null ? restore.getCreatedAt() : restore.getStartedAt();
        return started != null && !started.plusMillis(restoreTimeoutMs).isAfter(Instant.now());
    }
}
