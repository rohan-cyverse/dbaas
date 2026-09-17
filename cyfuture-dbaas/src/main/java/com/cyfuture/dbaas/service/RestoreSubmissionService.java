package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.entity.BackupMetadata;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.entity.RestoreRequestMetadata;
import com.cyfuture.dbaas.model.OperationStatus;
import com.cyfuture.dbaas.model.ProvisioningStage;
import com.cyfuture.dbaas.model.RestoreStatus;
import com.cyfuture.dbaas.model.BackupStatus;
import com.cyfuture.dbaas.model.BackupTriggerMethod;
import com.cyfuture.dbaas.model.BackupType;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import com.cyfuture.dbaas.repository.BackupMetadataRepository;
import com.cyfuture.dbaas.repository.OperationMetadataRepository;
import com.cyfuture.dbaas.repository.RestoreRequestMetadataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class RestoreSubmissionService {
    private final RestoreRequestMetadataRepository restoreRepository;
    private final BackupMetadataRepository backupRepository;
    private final DatabaseMetadataRepository databaseRepository;
    private final OperationMetadataRepository operationRepository;
    private final KubeBlocksClient kubeBlocksClient;
    private final BackupEngineStrategies strategies;
    private final BackupConfigurationNormalizer normalizer;
    private final BackupReconciler backupReconciler;
    private final OperationService operationService;

    @Async
    public void submit(String restoreId) {
        RestoreRequestMetadata restore = restoreRepository.findById(restoreId).orElse(null);
        if (restore == null || restore.getStatus() == RestoreStatus.READY
                || restore.getStatus() == RestoreStatus.COMPLETED
                || restore.getStatus() == RestoreStatus.FAILED
                || restore.getStatus() == RestoreStatus.CANCELLED) return;
        DatabaseMetadata target = databaseRepository.findByDatabaseIdAndProjectName(
                restore.getRestoredDatabaseId(), restore.getProjectName()).orElse(null);
        if (target == null) {
            fail(restore, "RESTORE_TARGET_METADATA_MISSING", "Restore target metadata is unavailable.");
            return;
        }
        BackupMetadata backup = backupRepository.findById(restore.getSourceBackupId()).orElse(null);
        if (backup == null || backup.getKubernetesBackupName() == null || backup.getKubernetesBackupName().isBlank()) {
            fail(restore, "BACKUP_NOT_AVAILABLE", "Backup correlation is unavailable for restore.");
            return;
        }
        try {
            if (operationService.cancellationRequested(restore.getOperationId())) {
                cancel(restore, target);
                return;
            }
            if (!safetyBackupReady(restore, target)) {
                return;
            }
            kubeBlocksClient.createRestoreOpsRequest(target.getNamespaceName(), restore.getProjectName(),
                    temporaryClusterName(restore), restore.getKubernetesOpsRequestName(),
                    backup.getKubernetesBackupName(), null, restore.getSourceBackupId(), restore.getOperationId(),
                    restore.getRestoreTime());
            restore.setStatus(RestoreStatus.RESTORING);
            if (restore.getStartedAt() == null) restore.setStartedAt(Instant.now());
            restore.setFailureCode(null);
            restore.setFailureMessage(null);
            restoreRepository.save(restore);
            updateOperation(restore, OperationStatus.RUNNING, ProvisioningStage.RESTORING_DATA,
                    20, "KubeBlocks Restore OpsRequest accepted for temporary cluster", false);
        } catch (Exception exception) {
            if (BackupRestoreSafety.retryable(exception)) {
                restore.setStatus(RestoreStatus.PENDING);
                restore.setFailureCode(BackupRestoreSafety.failureCode(exception, "RESTORE_SUBMISSION_RETRY"));
                restore.setFailureMessage(BackupRestoreSafety.safeMessage(exception,
                        "Restore submission will retry when Kubernetes is available."));
                restoreRepository.save(restore);
            } else {
                fail(restore, BackupRestoreSafety.failureCode(exception, "RESTORE_SUBMISSION_FAILED"),
                        BackupRestoreSafety.safeMessage(exception, "Restore submission failed."));
            }
        }
    }

    private boolean safetyBackupReady(RestoreRequestMetadata restore, DatabaseMetadata database) {
        if (restore.getSafetyBackupId() == null || restore.getSafetyBackupId().isBlank()) {
            BackupEngineStrategy strategy = strategies.require(database.getEngine());
            KubeBlocksClient.BackupPolicyInfo policy = kubeBlocksClient.resolveReadyBackupPolicy(
                    database.getNamespaceName(), database.physicalClusterName(), database.getEngine(),
                    strategy.manualFullMethod(), normalizer.repositoryName());
            String backupId = "bkp-safe-" + restore.getRestoreId().substring(4);
            BackupMetadata safety = new BackupMetadata();
            safety.setBackupId(backupId);
            safety.setOperationId(restore.getOperationId());
            safety.setProjectName(restore.getProjectName());
            safety.setDatabaseId(database.getDatabaseId());
            safety.setEngine(database.getEngine());
            safety.setBackupType(BackupType.FULL);
            safety.setBackupMethod(policy.backupMethod());
            safety.setTriggerMethod(BackupTriggerMethod.MANUAL);
            safety.setKubernetesBackupName(backupId);
            safety.setKubernetesPolicyName(policy.policyName());
            safety.setStatus(BackupStatus.PENDING);
            safety.setRetentionPeriod(normalizer.duration(normalizer.defaults().retentionDays()));
            safety.setIdempotencyKey("safety:" + restore.getRestoreId());
            safety.setRequestHash(restore.getRequestHash());
            safety.setCreatedAt(Instant.now());
            safety.setBaseBackupId(backupId);
            safety.setBaseKubernetesBackupName(backupId);
            backupRepository.save(safety);
            restore.setSafetyBackupId(backupId);
            restoreRepository.save(restore);
            kubeBlocksClient.createBackup(database.getNamespaceName(), restore.getProjectName(),
                    database.physicalClusterName(), safety.getKubernetesBackupName(), policy.policyName(),
                    policy.backupMethod(), safety.getRetentionPeriod(), null,
                    safety.getBackupId(), restore.getOperationId());
            safety.setStatus(BackupStatus.RUNNING);
            safety.setStartedAt(Instant.now());
            backupRepository.save(safety);
            restore.setStatus(RestoreStatus.SAFETY_BACKUP);
            restoreRepository.save(restore);
            updateOperation(restore, OperationStatus.RUNNING, ProvisioningStage.CREATING_SAFETY_BACKUP,
                    10, "Creating safety backup before restore", false);
            return false;
        }
        BackupMetadata safety = backupRepository.findById(restore.getSafetyBackupId()).orElse(null);
        if (safety == null) {
            fail(restore, "SAFETY_BACKUP_MISSING", "Safety backup metadata is unavailable.");
            return false;
        }
        backupReconciler.refresh(safety);
        BackupMetadata refreshed = backupRepository.findById(safety.getBackupId()).orElse(safety);
        if (refreshed.getStatus() == BackupStatus.COMPLETED) return true;
        if (refreshed.getStatus() == BackupStatus.FAILED) {
            fail(restore, "SAFETY_BACKUP_FAILED", "Safety backup failed; restore was not started.");
        } else {
            updateOperation(restore, OperationStatus.RUNNING, ProvisioningStage.CREATING_SAFETY_BACKUP,
                    12, "Waiting for safety backup to complete", false);
        }
        return false;
    }

    private void cancel(RestoreRequestMetadata restore, DatabaseMetadata database) {
        cleanupTemporaryCluster(restore, database);
        restore.setStatus(RestoreStatus.CANCELLED);
        restore.setFailureCode("RESTORE_CANCELLED");
        restore.setFailureMessage("Restore was cancelled before cutover; the original database was left untouched.");
        restore.setCompletedAt(Instant.now());
        restoreRepository.save(restore);
        if (database.getStatus() == com.cyfuture.dbaas.model.DatabaseStatus.MAINTENANCE) {
            database.setStatus(com.cyfuture.dbaas.model.DatabaseStatus.RUNNING);
            database.setProvisioningStage(ProvisioningStage.READY);
            database.setProgress(100);
        }
        database.setMessage("Restore cancelled");
        database.setUpdatedAt(Instant.now());
        databaseRepository.save(database);
        operationService.markCancelled(restore.getOperationId());
    }

    private void fail(RestoreRequestMetadata restore, String code, String message) {
        if (restore.getStatus() == RestoreStatus.CUTTING_OVER && !restore.isRollbackAttempted()
                && restore.getOldClusterName() != null && !restore.getOldClusterName().isBlank()) {
            restore.setRollbackAttempted(true);
            restore.setStatus(RestoreStatus.ROLLING_BACK);
            restore.setFailureCode(code);
            restore.setFailureMessage(message);
            restoreRepository.save(restore);
            databaseRepository.findByDatabaseIdAndProjectName(
                            restore.getRestoredDatabaseId(), restore.getProjectName())
                    .ifPresent(database -> {
                        database.setActiveClusterName(restore.getOldClusterName());
                        database.setStatus(com.cyfuture.dbaas.model.DatabaseStatus.RUNNING);
                        database.setProvisioningStage(ProvisioningStage.READY);
                        database.setProgress(100);
                        database.setMessage("Restore cutover rolled back");
                        database.setUpdatedAt(Instant.now());
                        databaseRepository.save(database);
                        cleanupTemporaryCluster(restore, database);
                    });
            updateOperation(restore, OperationStatus.RUNNING, ProvisioningStage.ROLLING_BACK,
                    80, "Restore cutover failed; rolling back to the previous cluster", false);
            return;
        }
        databaseRepository.findByDatabaseIdAndProjectName(restore.getRestoredDatabaseId(), restore.getProjectName())
                .ifPresent(database -> cleanupTemporaryCluster(restore, database));
        restore.setStatus(RestoreStatus.FAILED);
        restore.setFailureCode(code);
        restore.setFailureMessage(message);
        restore.setCompletedAt(Instant.now());
        restoreRepository.save(restore);
        updateOperation(restore, OperationStatus.FAILED, ProvisioningStage.FAILED, 100,
                "Restore failed.", true);
    }

    private String temporaryClusterName(RestoreRequestMetadata restore) {
        return restore.getTemporaryClusterName() == null || restore.getTemporaryClusterName().isBlank()
                ? restore.getRestoredDatabaseId() + "-restore-" + restore.getRestoreId().substring(4, 12)
                : restore.getTemporaryClusterName();
    }

    private void cleanupTemporaryCluster(RestoreRequestMetadata restore, DatabaseMetadata database) {
        String temporaryCluster = temporaryClusterName(restore);
        if (temporaryCluster.equals(database.physicalClusterName())) return;
        try {
            kubeBlocksClient.requestDelete(database.getNamespaceName(), temporaryCluster);
        } catch (Exception ignored) {
            // Scheduled reconciliation retries failure cleanup.
        }
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
}
