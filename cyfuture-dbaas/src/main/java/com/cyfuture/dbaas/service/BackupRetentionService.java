package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.entity.BackupMetadata;
import com.cyfuture.dbaas.model.BackupStatus;
import com.cyfuture.dbaas.repository.BackupMetadataRepository;
import com.cyfuture.dbaas.repository.RestoreRequestMetadataRepository;
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
                        com.cyfuture.dbaas.model.RestoreStatus.RUNNING));
    }

    /** Project deletion removes known backup data through the same backup DELETE path. */
    @Transactional
    public boolean prepareProjectBackupDeletion(String project) {
        boolean pending = false;
        for (BackupMetadata backup : backupRepository.findByProjectNameOrderByCreatedAtDesc(project)) {
            if (backup.getStatus() == BackupStatus.COMPLETED || backup.getStatus() == BackupStatus.FAILED) {
                if (restoreRepository.existsByProjectNameAndSourceBackupIdAndStatusIn(project, backup.getBackupId(),
                        List.of(com.cyfuture.dbaas.model.RestoreStatus.PENDING,
                                com.cyfuture.dbaas.model.RestoreStatus.RUNNING))) {
                    pending = true;
                    continue;
                }
                backup.setStatus(BackupStatus.DELETING);
                backup.setDeleteRequestedAt(Instant.now());
                backup.setFailureCode(null);
                backup.setFailureMessage(null);
                backupRepository.save(backup);
                String backupId = backup.getBackupId();
                submitAfterCommit(() -> deletionSubmitter.delete(backupId));
                pending = true;
            } else if (backup.getStatus() == BackupStatus.PENDING || backup.getStatus() == BackupStatus.RUNNING
                    || backup.getStatus() == BackupStatus.DELETING) {
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
