package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.entity.BackupMetadata;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.model.BackupStatus;
import com.cyfuture.dbaas.model.BackupTriggerMethod;
import com.cyfuture.dbaas.model.BackupType;
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

/** Reconciles DBaaS-known backups against KubeBlocks; unknown resources are never changed. */
@Service
@RequiredArgsConstructor
@Slf4j
public class BackupReconciler {
    private final BackupMetadataRepository backupRepository;
    private final DatabaseMetadataRepository databaseRepository;
    private final OperationMetadataRepository operationRepository;
    private final KubeBlocksClient kubeBlocksClient;
    private final BackupSubmissionService submissionService;
    private final BackupDeletionSubmitter deletionSubmitter;
    private final BackupRetentionService retentionService;

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() { reconcile(); }

    @Scheduled(fixedDelayString = "${dbaas.backup.reconcile-ms:5000}")
    public void reconcile() {
        for (BackupMetadata backup : backupRepository.findByStatusInOrderByCreatedAtAsc(
                List.of(BackupStatus.PENDING, BackupStatus.RUNNING, BackupStatus.DELETING,
                        BackupStatus.COMPLETED))) {
            try {
                refresh(backup);
            } catch (Exception exception) {
                if (!BackupRestoreSafety.retryable(exception)) {
                    fail(backup, BackupRestoreSafety.failureCode(exception, "BACKUP_RECONCILE_FAILED"),
                            BackupRestoreSafety.safeMessage(exception, "Backup reconciliation failed."));
                }
                log.debug("Backup reconciliation for {} will retry: {}", backup.getBackupId(),
                        BackupRestoreSafety.safeMessage(exception, "Backup reconciliation will retry."));
            }
        }
    }

    /** Performs one live-source refresh and is used by backup GET endpoints. */
    public void refresh(BackupMetadata backup) {
        if (backup.getStatus() == BackupStatus.PENDING
                && backup.getTriggerMethod() != BackupTriggerMethod.SCHEDULED) {
            submissionService.submit(backup.getBackupId());
            return;
        }
        if (backup.getStatus() == BackupStatus.DELETING) {
            reconcileDeletion(backup);
            return;
        }
        reconcileBackup(backup);
    }

    private void reconcileBackup(BackupMetadata backup) {
        String namespace = namespace(backup);
        if (namespace == null) {
            fail(backup, "BACKUP_SOURCE_METADATA_MISSING", "Backup source metadata is unavailable.");
            return;
        }
        KubeBlocksClient.BackupObservation observed = observe(backup, namespace);
        if (!observed.exists()) {
            markUnavailable(backup);
            return;
        }
        if ("Completed".equalsIgnoreCase(observed.phase())) {
            complete(backup, observed, namespace);
        } else if ("Failed".equalsIgnoreCase(observed.phase())) {
            fail(backup, "KUBERNETES_BACKUP_FAILED", observed.message());
        } else {
            running(backup, observed, namespace);
        }
    }

    private void reconcileDeletion(BackupMetadata backup) {
        String namespace = namespace(backup);
        if (namespace == null) {
            failDelete(backup, "BACKUP_SOURCE_METADATA_MISSING", "Backup source metadata is unavailable.");
            return;
        }
        KubeBlocksClient.BackupObservation observed = observe(backup, namespace);
        if (!observed.exists()) {
            backup.setStatus(backup.getExpiresAt() != null && !backup.getExpiresAt().isAfter(Instant.now())
                    ? BackupStatus.EXPIRED : BackupStatus.DELETED);
            backup.setDeletedAt(Instant.now());
            backup.setLastObservedAt(Instant.now());
            backupRepository.save(backup);
            return;
        }
        deletionSubmitter.delete(backup.getBackupId());
    }

    private String namespace(BackupMetadata backup) {
        return databaseRepository.findByDatabaseIdAndProjectName(backup.getDatabaseId(), backup.getProjectName())
                .map(DatabaseMetadata::getNamespaceName).orElse(null);
    }

    private KubeBlocksClient.BackupObservation observe(BackupMetadata backup, String namespace) {
        return kubeBlocksClient.observeManagedBackup(namespace, backup.getProjectName(), backup.getDatabaseId(),
                backup.getBackupId(), backup.getOperationId(), backup.getKubernetesBackupName(),
                backup.getKubernetesUid(), backup.getKubernetesPolicyName());
    }

    private void running(BackupMetadata backup, KubeBlocksClient.BackupObservation observed, String namespace) {
        backup.setStatus(BackupStatus.RUNNING);
        backup.setSizeBytes(observed.sizeBytes());
        if (observed.startedAt() != null) backup.setStartedAt(observed.startedAt());
        if (observed.uid() != null) backup.setKubernetesUid(observed.uid());
        if (observed.expiration() != null) backup.setExpiresAt(observed.expiration());
        applyObservedPitrFields(backup, observed, namespace);
        backup.setLastObservedAt(Instant.now());
        backupRepository.save(backup);
        updateOperation(backup, OperationStatus.RUNNING, ProvisioningStage.WAITING_FOR_REPLICAS, 50,
                "Backup is running", false);
    }

    private void complete(BackupMetadata backup, KubeBlocksClient.BackupObservation observed, String namespace) {
        boolean newlyCompleted = backup.getStatus() != BackupStatus.COMPLETED;
        backup.setStatus(BackupStatus.COMPLETED);
        backup.setSizeBytes(observed.sizeBytes());
        if (observed.startedAt() != null) backup.setStartedAt(observed.startedAt());
        if (observed.uid() != null) backup.setKubernetesUid(observed.uid());
        backup.setCompletedAt(observed.completedAt() == null ? Instant.now() : observed.completedAt());
        if (observed.expiration() != null) backup.setExpiresAt(observed.expiration());
        applyObservedPitrFields(backup, observed, namespace);
        backup.setFailureCode(null);
        backup.setFailureMessage(null);
        backup.setLastObservedAt(Instant.now());
        backupRepository.save(backup);
        updateOperation(backup, OperationStatus.SUCCEEDED, ProvisioningStage.READY, 100,
                "Backup completed", true);
        if (newlyCompleted && (backup.getBackupType() == BackupType.FULL
                || backup.getBackupType() == BackupType.INCREMENTAL)) {
            retentionService.recordCompletion(backup.getBackupId());
        }
    }

    private void applyObservedPitrFields(BackupMetadata backup,
                                         KubeBlocksClient.BackupObservation observed,
                                         String namespace) {
        if (backup.getBackupType() == BackupType.FULL) {
            backup.setBaseBackupId(backup.getBackupId());
            backup.setBaseKubernetesBackupName(backup.getKubernetesBackupName());
            backup.setParentBackupId(null);
            return;
        }
        if (observed.parentBackupName() != null && !observed.parentBackupName().isBlank()) {
            backup.setParentKubernetesBackupName(observed.parentBackupName());
        }
        if (observed.baseBackupName() != null && !observed.baseBackupName().isBlank()) {
            backup.setBaseKubernetesBackupName(observed.baseBackupName());
        }
        backup.setCoverageStart(observed.coverageStart());
        backup.setCoverageEnd(observed.coverageEnd());
        if (observed.parentBackupName() != null) {
            backupRepository.findByProjectNameAndDatabaseIdAndKubernetesBackupName(
                    backup.getProjectName(), backup.getDatabaseId(), observed.parentBackupName())
                    .ifPresent(parent -> backup.setParentBackupId(parent.getBackupId()));
        }
        if (observed.baseBackupName() != null) {
            backupRepository.findByProjectNameAndDatabaseIdAndKubernetesBackupName(
                    backup.getProjectName(), backup.getDatabaseId(), observed.baseBackupName())
                    .ifPresent(base -> backup.setBaseBackupId(base.getBackupId()));
        }
    }

    private void markUnavailable(BackupMetadata backup) {
        boolean expired = backup.getExpiresAt() != null && !backup.getExpiresAt().isAfter(Instant.now());
        backup.setStatus(expired ? BackupStatus.EXPIRED : BackupStatus.DELETED);
        if (backup.getDeletedAt() == null) backup.setDeletedAt(Instant.now());
        backup.setLastObservedAt(Instant.now());
        backupRepository.save(backup);
    }

    private void fail(BackupMetadata backup, String code, String message) {
        backup.setStatus(BackupStatus.FAILED);
        backup.setFailureCode(code);
        backup.setFailureMessage(BackupRestoreSafety.safeMessage(null, message));
        if (backup.getCompletedAt() == null) backup.setCompletedAt(Instant.now());
        backup.setLastObservedAt(Instant.now());
        backupRepository.save(backup);
        updateOperation(backup, OperationStatus.FAILED, ProvisioningStage.FAILED, 100, "Backup failed", true);
    }

    private void failDelete(BackupMetadata backup, String code, String message) {
        backup.setStatus(backup.getCompletedAt() == null ? BackupStatus.FAILED : BackupStatus.COMPLETED);
        backup.setFailureCode(code);
        backup.setFailureMessage(BackupRestoreSafety.safeMessage(null, message));
        backup.setLastObservedAt(Instant.now());
        backupRepository.save(backup);
    }

    private void updateOperation(BackupMetadata backup, OperationStatus status,
                                 ProvisioningStage stage, int progress, String message, boolean terminal) {
        operationRepository.findById(backup.getOperationId()).ifPresent(operation -> {
            if (operation.getStatus() == status && operation.getProgress() == progress
                    && Objects.equals(operation.getMessage(), message)) return;
            operation.setStatus(status);
            operation.setProvisioningStage(stage);
            operation.setProgress(progress);
            operation.setMessage(message);
            if (operation.getStartedAt() == null) operation.setStartedAt(Instant.now());
            if (terminal) operation.setCompletedAt(Instant.now());
            operationRepository.save(operation);
        });
    }
}
