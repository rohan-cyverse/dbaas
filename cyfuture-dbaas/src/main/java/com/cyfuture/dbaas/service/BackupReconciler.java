package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.entity.BackupMetadata;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.model.BackupDeletionMode;
import com.cyfuture.dbaas.model.BackupRetentionPolicy;
import com.cyfuture.dbaas.model.BackupStatus;
import com.cyfuture.dbaas.model.BackupTriggerMethod;
import com.cyfuture.dbaas.model.OperationStatus;
import com.cyfuture.dbaas.model.ProvisioningStage;
import com.cyfuture.dbaas.repository.BackupMetadataRepository;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import com.cyfuture.dbaas.repository.OperationMetadataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Reconciles each known Backup CR independently; it never garbage-collects unknown resources. */
@Service
@RequiredArgsConstructor
@Slf4j
public class BackupReconciler {
    private final BackupMetadataRepository backupRepository;
    private final DatabaseMetadataRepository databaseRepository;
    private final OperationMetadataRepository operationRepository;
    private final KubeBlocksClient kubeBlocksClient;
    private final BackupSubmissionService submissionService;
    private final BackupPurgeSubmitter purgeSubmitter;
    private final BackupRetentionService retentionService;

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() { reconcile(); }

    @Scheduled(fixedDelayString = "${dbaas.backup.reconcile-ms:5000}")
    public void reconcile() {
        for (BackupMetadata backup : backupRepository.findByStatusInOrderByCreatedAtAsc(
                List.of(BackupStatus.PENDING, BackupStatus.RUNNING, BackupStatus.DELETING,
                        BackupStatus.COMPLETED))) {
            try {
                if (backup.getStatus() == BackupStatus.PENDING
                        && backup.getTriggerMethod() != BackupTriggerMethod.AUTOMATIC) {
                    submissionService.submit(backup.getBackupId());
                } else if (backup.getStatus() == BackupStatus.DELETING) {
                    reconcilePurge(backup);
                } else if (backup.getStatus() == BackupStatus.COMPLETED) {
                    reconcileAvailableBackup(backup);
                } else {
                    reconcileBackup(backup);
                }
            } catch (Exception exception) {
                if (!BackupRestoreSafety.retryable(exception)) {
                    String code = BackupRestoreSafety.failureCode(exception, "BACKUP_RECONCILE_FAILED");
                    String message = BackupRestoreSafety.safeMessage(exception,
                            "Backup reconciliation failed.");
                    if (backup.getStatus() == BackupStatus.DELETING) {
                        failDelete(backup, code, message);
                    } else {
                        fail(backup, backup.getOperationId(), code, message);
                    }
                }
                log.debug("Backup reconciliation for {} will retry: {}", backup.getBackupId(),
                        BackupRestoreSafety.safeMessage(exception, "Backup reconciliation will retry."));
            }
        }
    }

    void reconcileBackup(BackupMetadata backup) {
        String namespace = namespace(backup);
        if (namespace == null) {
            fail(backup, backup.getOperationId(), "BACKUP_SOURCE_METADATA_MISSING",
                    "Backup source metadata is unavailable.");
            return;
        }
        KubeBlocksClient.BackupObservation observed = observe(backup, namespace);
        if (!observed.exists()) {
            if (backup.getExpiresAt() != null && !backup.getExpiresAt().isAfter(Instant.now())) {
                expire(backup, "KubeBlocks backup retention period expired.");
            } else {
                fail(backup, backup.getOperationId(), "KUBERNETES_BACKUP_NOT_FOUND",
                        "The KubeBlocks Backup resource is no longer available.");
            }
            return;
        }
        if ("Completed".equalsIgnoreCase(observed.phase())) {
            complete(backup, observed);
        } else if ("Failed".equalsIgnoreCase(observed.phase())) {
            fail(backup, backup.getOperationId(), "KUBERNETES_BACKUP_FAILED", observed.message());
        } else if ("Deleting".equalsIgnoreCase(observed.phase())) {
            boolean changed = backup.getStatus() != BackupStatus.DELETING;
            if (observed.expiration() != null && !Objects.equals(backup.getExpiresAt(), observed.expiration())) {
                backup.setExpiresAt(observed.expiration());
                changed = true;
            }
            if (changed) {
                backup.setStatus(BackupStatus.DELETING);
                backup.setLastObservedAt(Instant.now());
                backupRepository.save(backup);
            }
        } else {
            updateRunning(backup, observed);
        }
    }

    /** Keeps the Backup Set truthful when KubeBlocks later expires or removes a known CR. */
    private void reconcileAvailableBackup(BackupMetadata backup) {
        String namespace = namespace(backup);
        if (namespace == null) {
            markUnavailable(backup, backup.getExpiresAt() != null
                    && !backup.getExpiresAt().isAfter(Instant.now()));
            return;
        }
        KubeBlocksClient.BackupObservation observed = observe(backup, namespace);
        if (!observed.exists()) {
            boolean expired = backup.getExpiresAt() != null && !backup.getExpiresAt().isAfter(Instant.now());
            markUnavailable(backup, expired);
            return;
        }
        boolean changed = !Objects.equals(backup.getSizeBytes(), observed.sizeBytes())
                || !Objects.equals(backup.getStartedAt(), observed.startedAt())
                || !Objects.equals(backup.getKubernetesUid(), observed.uid())
                || (observed.expiration() != null
                && !Objects.equals(backup.getExpiresAt(), observed.expiration()));
        if (!changed) return;
        backup.setSizeBytes(observed.sizeBytes());
        if (observed.startedAt() != null) backup.setStartedAt(observed.startedAt());
        if (observed.uid() != null) backup.setKubernetesUid(observed.uid());
        if (observed.expiration() != null) backup.setExpiresAt(observed.expiration());
        backup.setLastObservedAt(Instant.now());
        backupRepository.save(backup);
    }

    private void reconcilePurge(BackupMetadata backup) {
        String namespace = namespace(backup);
        if (namespace == null) {
            failDelete(backup, "BACKUP_NAMESPACE_UNAVAILABLE",
                    "Backup namespace is unavailable for deletion.");
            return;
        }
        KubeBlocksClient.BackupObservation observed = observe(backup, namespace);
        if (!observed.exists()) {
            Instant now = Instant.now();
            boolean expired = backup.getExpiresAt() != null && !backup.getExpiresAt().isAfter(now);
            backup.setStatus(expired ? BackupStatus.EXPIRED : BackupStatus.DELETED);
            backup.setDeletedAt(now);
            if (backup.getDeletionMode() == BackupDeletionMode.PURGE_DATA) backup.setPurgedAt(now);
            backup.setLastObservedAt(now);
            backupRepository.save(backup);
            finishOperation(backup.getDeleteOperationId(), OperationStatus.SUCCEEDED,
                    ProvisioningStage.READY, 100, expired ? "Backup expired" : "Backup deletion completed");
            return;
        }
        // A generated BackupSchedule or KubeBlocks retention may remove its
        // own Backup CR. That is an observed deletion, not authorization for
        // DBaaS to issue another delete or change its deletionPolicy. Only a
        // user/retention initiated deletion has a durable delete operation.
        if (backup.getDeleteOperationId() == null || backup.getDeleteOperationId().isBlank()) {
            return;
        }
        purgeSubmitter.purge(backup.getBackupId());
    }

    private String namespace(BackupMetadata backup) {
        if (backup.getKubernetesNamespace() != null && !backup.getKubernetesNamespace().isBlank()) {
            return backup.getKubernetesNamespace();
        }
        return databaseRepository.findByDatabaseIdAndProjectName(backup.getDatabaseId(), backup.getProjectName())
                .map(DatabaseMetadata::getNamespaceName).orElse(null);
    }

    private KubeBlocksClient.BackupObservation observe(BackupMetadata backup, String namespace) {
        return kubeBlocksClient.observeManagedBackup(namespace, backup.getProjectName(), backup.getDatabaseId(),
                backup.getBackupId(), backup.getOperationId(), backup.getKubernetesBackupName(),
                backup.getKubernetesUid(), backup.getKubernetesPolicyName());
    }

    private void updateRunning(BackupMetadata backup, KubeBlocksClient.BackupObservation observed) {
        boolean changed = backup.getStatus() != BackupStatus.RUNNING
                || !Objects.equals(backup.getSizeBytes(), observed.sizeBytes())
                || !Objects.equals(backup.getStartedAt(), observed.startedAt())
                || !Objects.equals(backup.getKubernetesUid(), observed.uid());
        if (!changed) return;
        backup.setStatus(BackupStatus.RUNNING);
        backup.setSizeBytes(observed.sizeBytes());
        if (observed.startedAt() != null) backup.setStartedAt(observed.startedAt());
        if (observed.uid() != null) backup.setKubernetesUid(observed.uid());
        backup.setLastObservedAt(Instant.now());
        backupRepository.save(backup);
        finishOperation(backup.getOperationId(), OperationStatus.RUNNING,
                ProvisioningStage.WAITING_FOR_REPLICAS, 50, "KubeBlocks is processing the backup");
    }

    private void complete(BackupMetadata backup, KubeBlocksClient.BackupObservation observed) {
        backup.setStatus(BackupStatus.COMPLETED);
        backup.setSizeBytes(observed.sizeBytes());
        if (observed.startedAt() != null) backup.setStartedAt(observed.startedAt());
        if (observed.uid() != null) backup.setKubernetesUid(observed.uid());
        backup.setCompletedAt(observed.completedAt() == null ? Instant.now() : observed.completedAt());
        if (observed.expiration() != null) backup.setExpiresAt(observed.expiration());
        backup.setLastObservedAt(Instant.now());
        backup.setFailureCode(null);
        backup.setFailureMessage(null);
        backupRepository.save(backup);
        finishOperation(backup.getOperationId(), OperationStatus.SUCCEEDED,
                ProvisioningStage.READY, 100, "Backup completed");
        try {
            // The recovery point is already complete. Retention coordination
            // can be retried independently and must never reclassify a
            // successful KubeBlocks backup as failed because of metadata I/O.
            retentionService.recordCompletion(backup.getBackupId());
        } catch (RuntimeException exception) {
            log.debug("Retention reconciliation for completed backup {} will retry: {}", backup.getBackupId(),
                    BackupRestoreSafety.safeMessage(exception, "Retention reconciliation will retry."));
        }
    }

    private void expire(BackupMetadata backup, String message) {
        backup.setStatus(BackupStatus.EXPIRED);
        if (backup.getDeletedAt() == null) backup.setDeletedAt(Instant.now());
        backup.setFailureCode(null);
        backup.setFailureMessage(null);
        backup.setLastObservedAt(Instant.now());
        backupRepository.save(backup);
        finishOperation(backup.getOperationId(), OperationStatus.SUCCEEDED,
                ProvisioningStage.READY, 100, message);
    }

    private void markUnavailable(BackupMetadata backup, boolean expired) {
        Instant now = Instant.now();
        backup.setStatus(expired ? BackupStatus.EXPIRED : BackupStatus.DELETED);
        if (backup.getDeletedAt() == null) backup.setDeletedAt(now);
        backup.setLastObservedAt(now);
        backupRepository.save(backup);
    }

    private void fail(BackupMetadata backup, String operationId, String code, String message) {
        backup.setStatus(BackupStatus.FAILED);
        backup.setFailureCode(code);
        backup.setFailureMessage(BackupRestoreSafety.safeMessage(null, message));
        if (backup.getCompletedAt() == null) backup.setCompletedAt(Instant.now());
        backup.setLastObservedAt(Instant.now());
        backupRepository.save(backup);
        finishOperation(operationId, OperationStatus.FAILED, ProvisioningStage.FAILED, 100, "Backup failed");
    }

    private void failDelete(BackupMetadata backup, String code, String message) {
        // Retain an already completed recovery point when its deletion fails.
        if (backup.getCompletedAt() != null) backup.setStatus(BackupStatus.COMPLETED);
        else backup.setStatus(BackupStatus.FAILED);
        backup.setFailureCode(code);
        backup.setFailureMessage(BackupRestoreSafety.safeMessage(null, message));
        backup.setLastObservedAt(Instant.now());
        backupRepository.save(backup);
        finishOperation(backup.getDeleteOperationId(), OperationStatus.FAILED,
                ProvisioningStage.FAILED, 100, "Backup deletion failed");
    }

    private void finishOperation(String operationId, OperationStatus status,
                                 ProvisioningStage stage, int progress, String message) {
        if (operationId == null) return;
        operationRepository.findById(operationId).ifPresent(operation -> {
            if (operation.getStatus() == status && operation.getProgress() == progress
                    && Objects.equals(operation.getMessage(), message)) return;
            operation.setStatus(status);
            operation.setProvisioningStage(stage);
            operation.setProgress(progress);
            operation.setMessage(message);
            if (operation.getStartedAt() == null) operation.setStartedAt(Instant.now());
            if (status == OperationStatus.SUCCEEDED || status == OperationStatus.FAILED) {
                operation.setCompletedAt(Instant.now());
            }
            operationRepository.save(operation);
        });
    }
}
