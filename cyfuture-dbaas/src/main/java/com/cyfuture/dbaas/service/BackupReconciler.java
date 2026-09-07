package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.entity.BackupMetadata;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.model.BackupStatus;
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

/** Reconciles each known CR independently; it never garbage-collects unknown backups. */
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

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() { reconcile(); }

    @Scheduled(fixedDelayString = "${dbaas.backup.reconcile-ms:5000}")
    public void reconcile() {
        for (BackupMetadata backup : backupRepository.findByStatusInOrderByCreatedAtAsc(
                List.of(BackupStatus.PENDING, BackupStatus.RUNNING, BackupStatus.DELETING))) {
            try {
                if (backup.getStatus() == BackupStatus.PENDING) {
                    submissionService.submit(backup.getBackupId());
                } else if (backup.getStatus() == BackupStatus.DELETING) {
                    reconcilePurge(backup);
                } else {
                    reconcileBackup(backup);
                }
            } catch (Exception exception) {
                log.debug("Backup reconciliation for {} will retry: {}", backup.getBackupId(), exception.getMessage());
            }
        }
    }

    void reconcileBackup(BackupMetadata backup) {
        DatabaseMetadata source = databaseRepository
                .findByDatabaseIdAndProjectName(backup.getDatabaseId(), backup.getProjectName()).orElse(null);
        if (source == null) {
            fail(backup, backup.getOperationId(), "BACKUP_SOURCE_METADATA_MISSING", "Backup source metadata is unavailable.");
            return;
        }
        KubeBlocksClient.BackupObservation observed = kubeBlocksClient.observeBackup(
                source.getNamespaceName(), backup.getKubernetesBackupName());
        if (!observed.exists()) {
            fail(backup, backup.getOperationId(), "KUBERNETES_BACKUP_NOT_FOUND",
                    "The KubeBlocks Backup resource is no longer available.");
            return;
        }
        if ("Completed".equalsIgnoreCase(observed.phase())) {
            complete(backup, observed);
        } else if ("Failed".equalsIgnoreCase(observed.phase())) {
            fail(backup, backup.getOperationId(), "KUBERNETES_BACKUP_FAILED", observed.message());
        } else if ("Deleting".equalsIgnoreCase(observed.phase())) {
            if (backup.getStatus() != BackupStatus.DELETING) {
                backup.setStatus(BackupStatus.DELETING);
                backupRepository.save(backup);
            }
        } else {
            updateRunning(backup, observed);
        }
    }

    private void reconcilePurge(BackupMetadata backup) {
        DatabaseMetadata source = databaseRepository
                .findByDatabaseIdAndProjectName(backup.getDatabaseId(), backup.getProjectName()).orElse(null);
        if (source == null) {
            fail(backup, backup.getDeleteOperationId(), "BACKUP_SOURCE_METADATA_MISSING",
                    "Backup source metadata is unavailable for purge.");
            return;
        }
        KubeBlocksClient.BackupObservation observed = kubeBlocksClient.observeBackup(
                source.getNamespaceName(), backup.getKubernetesBackupName());
        if (!observed.exists()) {
            backup.setStatus(BackupStatus.DELETED);
            backup.setDeletedAt(Instant.now());
            backup.setLastObservedAt(Instant.now());
            backupRepository.save(backup);
            finishOperation(backup.getDeleteOperationId(), OperationStatus.SUCCEEDED,
                    ProvisioningStage.READY, 100, "Backup purge completed");
            return;
        }
        purgeSubmitter.purge(backup.getBackupId());
    }

    private void updateRunning(BackupMetadata backup, KubeBlocksClient.BackupObservation observed) {
        boolean changed = backup.getStatus() != BackupStatus.RUNNING
                || !Objects.equals(backup.getSizeBytes(), observed.sizeBytes())
                || !Objects.equals(backup.getStartedAt(), observed.startedAt());
        if (!changed) return;
        backup.setStatus(BackupStatus.RUNNING);
        backup.setSizeBytes(observed.sizeBytes());
        if (observed.startedAt() != null) backup.setStartedAt(observed.startedAt());
        backup.setLastObservedAt(Instant.now());
        backupRepository.save(backup);
        finishOperation(backup.getOperationId(), OperationStatus.RUNNING,
                ProvisioningStage.WAITING_FOR_REPLICAS, 50, "KubeBlocks is processing the backup");
    }

    private void complete(BackupMetadata backup, KubeBlocksClient.BackupObservation observed) {
        backup.setStatus(BackupStatus.COMPLETED);
        backup.setSizeBytes(observed.sizeBytes());
        if (observed.startedAt() != null) backup.setStartedAt(observed.startedAt());
        backup.setCompletedAt(observed.completedAt() == null ? Instant.now() : observed.completedAt());
        backup.setLastObservedAt(Instant.now());
        backup.setFailureCode(null);
        backup.setFailureMessage(null);
        backupRepository.save(backup);
        finishOperation(backup.getOperationId(), OperationStatus.SUCCEEDED,
                ProvisioningStage.READY, 100, "Backup completed");
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
