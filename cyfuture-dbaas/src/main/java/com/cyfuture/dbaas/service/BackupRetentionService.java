package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.entity.BackupMetadata;
import com.cyfuture.dbaas.entity.RestoreRequestMetadata;
import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.model.BackupStatus;
import com.cyfuture.dbaas.repository.BackupMetadataRepository;
import com.cyfuture.dbaas.repository.RestoreRequestMetadataRepository;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;

/** Coordinates only DBaaS lifecycle safety; KubeBlocks enforces backup expiry. */
@Service
@RequiredArgsConstructor
public class BackupRetentionService {
    private final BackupMetadataRepository backupRepository;
    private final RestoreRequestMetadataRepository restoreRepository;
    private final BackupDeletionSubmitter deletionSubmitter;
    private final DatabaseMetadataRepository databaseRepository;
    private final KubeBlocksClient kubeBlocksClient;

    /** Completion is already represented by KubeBlocks and needs no duplicate retention policy. */
    @Transactional
    public void recordCompletion(String backupId) {
        backupRepository.findById(backupId).ifPresent(this::markExpiredWhenObserved);
    }

    @Scheduled(fixedDelayString = "${dbaas.backup.reconcile-ms:5000}")
    @Transactional
    public void reconcileExpiry() {
        backupRepository.findByStatusInOrderByCreatedAtAsc(List.of(BackupStatus.COMPLETED))
                .forEach(this::markExpiredWhenObserved);
    }

    /** Blocks source deletion only while a backup or restore can still be mutated. */
    public boolean readyForClusterDeletion(String project, String databaseId) {
        return !backupRepository.existsByProjectNameAndDatabaseIdAndStatusIn(project, databaseId,
                List.of(BackupStatus.PENDING, BackupStatus.RUNNING, BackupStatus.DELETING))
                && !restoreRepository.existsByProjectNameAndSourceDatabaseIdAndStatusIn(project, databaseId,
                List.of(com.cyfuture.dbaas.model.RestoreStatus.PENDING,
                        com.cyfuture.dbaas.model.RestoreStatus.SAFETY_BACKUP,
                        com.cyfuture.dbaas.model.RestoreStatus.RESTORING,
                        com.cyfuture.dbaas.model.RestoreStatus.VALIDATING,
                        com.cyfuture.dbaas.model.RestoreStatus.CUTTING_OVER,
                        com.cyfuture.dbaas.model.RestoreStatus.ROLLING_BACK,
                        com.cyfuture.dbaas.model.RestoreStatus.RUNNING));
    }

    /** Project deletion removes known backup data through the same backup DELETE path. */
    @Transactional
    public boolean prepareProjectBackupDeletion(String project) {
        boolean backupsReady = prepareBackupDeletion(project, null);
        deleteRestoreResources(project, null);
        return backupsReady;
    }

    /** Database deletion removes every known backup before deleting its source cluster. */
    @Transactional
    public boolean prepareDatabaseBackupDeletion(String project, String databaseId) {
        boolean backupsReady = prepareBackupDeletion(project, databaseId);
        deleteRestoreResources(project, databaseId);
        return backupsReady;
    }

    private void deleteRestoreResources(String project, String databaseId) {
        List<RestoreRequestMetadata> restores = restoreRepository.findByProjectNameOrderByCreatedAtDesc(project)
                .stream()
                .filter(restore -> databaseId == null
                        || databaseId.equals(restore.getSourceDatabaseId())
                        || databaseId.equals(restore.getRestoredDatabaseId()))
                .toList();
        for (RestoreRequestMetadata restore : restores) {
            String namespace = databaseRepository.findByDatabaseIdAndProjectName(
                            restore.getSourceDatabaseId(), project)
                    .or(() -> databaseRepository.findByDatabaseIdAndProjectName(
                            restore.getRestoredDatabaseId(), project))
                    .map(database -> database.getNamespaceName())
                    .orElse(null);
            if (namespace == null || namespace.isBlank()) continue;
            try {
                kubeBlocksClient.deleteManagedRestore(namespace, project, restore.getSourceDatabaseId(),
                        restore.getOperationId(), restore.getKubernetesOpsRequestName(),
                        restore.getKubernetesRestoreName());
                restoreRepository.delete(restore);
            } catch (Exception ignored) {
                // The next deletion reconciliation retries transient Kubernetes failures.
            }
        }
    }

    private boolean prepareBackupDeletion(String project, String databaseId) {
        boolean pending = false;
        List<BackupMetadata> backups = databaseId == null
                ? backupRepository.findByProjectNameOrderByCreatedAtDesc(project)
                : backupRepository.findByProjectNameAndDatabaseIdOrderByCreatedAtDesc(project, databaseId);
        for (BackupMetadata backup : backups) {
            if (backup.getStatus() != BackupStatus.DELETED
                    && backup.getStatus() != BackupStatus.EXPIRED
                    && backup.getStatus() != BackupStatus.DELETING) {
                backup.setStatus(BackupStatus.DELETING);
                backup.setDeleteRequestedAt(Instant.now());
                backup.setFailureCode(null);
                backup.setFailureMessage(null);
                backupRepository.save(backup);
                String backupId = backup.getBackupId();
                submitAfterCommit(() -> deletionSubmitter.delete(backupId));
                pending = true;
            } else if (backup.getStatus() == BackupStatus.DELETING) {
                pending = true;
            }
        }
        return !pending;
    }

    private void markExpiredWhenObserved(BackupMetadata backup) {
        if (backup.getExpiresAt() != null && !backup.getExpiresAt().isAfter(Instant.now())) {
            backup.setStatus(BackupStatus.EXPIRED);
            if (backup.getDeletedAt() == null) backup.setDeletedAt(Instant.now());
            backupRepository.save(backup);
        }
    }

    private void submitAfterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { action.run(); }
        });
    }
}
