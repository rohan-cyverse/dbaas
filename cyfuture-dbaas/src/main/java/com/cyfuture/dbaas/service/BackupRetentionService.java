package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.entity.BackupMetadata;
import com.cyfuture.dbaas.entity.BackupPolicyMetadata;
import com.cyfuture.dbaas.entity.OperationMetadata;
import com.cyfuture.dbaas.model.BackupDeletionMode;
import com.cyfuture.dbaas.model.BackupRetentionPolicy;
import com.cyfuture.dbaas.model.BackupStatus;
import com.cyfuture.dbaas.model.OperationStatus;
import com.cyfuture.dbaas.model.OperationType;
import com.cyfuture.dbaas.model.ProvisioningStage;
import com.cyfuture.dbaas.repository.BackupMetadataRepository;
import com.cyfuture.dbaas.repository.BackupPolicyMetadataRepository;
import com.cyfuture.dbaas.repository.OperationMetadataRepository;
import com.cyfuture.dbaas.repository.RestoreRequestMetadataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/** Implements retention only after a replacement backup completed successfully. */
@Service
@RequiredArgsConstructor
public class BackupRetentionService {
    private final BackupMetadataRepository backupRepository;
    private final BackupPolicyMetadataRepository policyRepository;
    private final RestoreRequestMetadataRepository restoreRepository;
    private final OperationMetadataRepository operationRepository;
    private final BackupPurgeSubmitter purgeSubmitter;
    private final BackupConfigurationNormalizer normalizer;

    @Transactional
    public void recordCompletion(String backupId) {
        BackupMetadata completed = backupRepository.findByBackupIdForUpdate(backupId).orElse(null);
        if (completed == null || completed.getStatus() != BackupStatus.COMPLETED) return;
        Instant completion = completed.getCompletedAt() == null ? Instant.now() : completed.getCompletedAt();
        if (completed.getCompletedAt() == null) completed.setCompletedAt(completion);
        BackupRetentionPolicy policy = completed.getRetentionPolicy() == null
                ? BackupRetentionPolicy.RETAIN_ALL : completed.getRetentionPolicy();
        if (policy == BackupRetentionPolicy.RETAIN_ALL) {
            int days = normalizer.retentionDays(completed.getRetentionPeriod());
            Instant expires = completion.plus(days, ChronoUnit.DAYS);
            if (!expires.equals(completed.getExpiresAt())) completed.setExpiresAt(expires);
            backupRepository.save(completed);
            return;
        }
        if (policy != BackupRetentionPolicy.RETAIN_LATEST) {
            backupRepository.save(completed);
            return;
        }
        // The completed replacement is saved before any predecessor is queued
        // for purge. Failed backups never invoke this method and therefore can
        // never replace the most recent successful recovery point.
        backupRepository.save(completed);
        List<BackupMetadata> candidates = backupRepository
                .findByProjectNameAndDatabaseIdAndStatusOrderByCompletedAtDesc(
                        completed.getProjectName(), completed.getDatabaseId(), BackupStatus.COMPLETED);
        if (candidates.isEmpty() || !completed.getBackupId().equals(candidates.get(0).getBackupId())) {
            // This is an older completion discovered during a restart scan.
            // It must never cause the newer recovery point to be removed.
            return;
        }
        for (int index = 1; index < candidates.size(); index++) {
            BackupMetadata previous = candidates.get(index);
            // Retention is snapshotted on each backup. A later switch to
            // RETAIN_LATEST must not retroactively discard an older
            // RETAIN_ALL recovery point before its own expiration.
            if (previous.getRetentionPolicy() != BackupRetentionPolicy.RETAIN_LATEST) continue;
            queuePurge(previous, "retention:" + completed.getBackupId(), "Retention replacement completed");
        }
    }

    /** Restart-safe pass for policy retention and individual RETAIN_ALL expiration. */
    @Transactional
    @Scheduled(fixedDelayString = "${dbaas.backup.reconcile-ms:5000}")
    public void reconcileRetention() {
        for (BackupMetadata backup : backupRepository.findByStatusInOrderByCreatedAtAsc(
                List.of(BackupStatus.COMPLETED))) {
            try {
                BackupRetentionPolicy policy = backup.getRetentionPolicy() == null
                        ? BackupRetentionPolicy.RETAIN_ALL : backup.getRetentionPolicy();
                if (policy == BackupRetentionPolicy.RETAIN_LATEST) {
                    recordCompletion(backup.getBackupId());
                } else if (policy == BackupRetentionPolicy.RETAIN_ALL
                        && backup.getExpiresAt() != null && !backup.getExpiresAt().isAfter(Instant.now())) {
                    queuePurge(backup, "expiration:" + backup.getBackupId(), "Backup retention period expired");
                }
            } catch (Exception ignored) {
                // Each backup is independent; a transient error must not stop
                // reconciliation of another project's retention work.
            }
        }
    }

    /**
     * Queues purge operations required by DELETE_ALL. Returns true only once
     * no backup operation remains active for the database.
     */
    @Transactional
    public boolean prepareDatabaseDeletion(String project, String databaseId) {
        BackupPolicyMetadata policy = policyRepository.findByProjectNameAndDatabaseId(project, databaseId)
                .orElse(null);
        if (policy == null || policy.getRetentionPolicy() != BackupRetentionPolicy.DELETE_ALL) return true;
        boolean pending = false;
        for (BackupMetadata backup : backupRepository.findByProjectNameAndDatabaseIdOrderByCreatedAtDesc(project, databaseId)) {
            if (backup.getStatus() == BackupStatus.PENDING || backup.getStatus() == BackupStatus.RUNNING
                    || backup.getStatus() == BackupStatus.DELETING) {
                pending = true;
                continue;
            }
            if (backup.getStatus() == BackupStatus.COMPLETED || backup.getStatus() == BackupStatus.FAILED) {
                queuePurge(backup, "database-delete:" + databaseId,
                        "DELETE_ALL retention policy requires backup purge");
                pending = true;
            }
        }
        return !pending;
    }

    /**
     * Guards Cluster deletion. Active backup work always blocks deletion; a
     * DELETE_ALL policy queues data purges before Kubernetes sees the Cluster
     * delete request.
     */
    @Transactional
    public boolean readyForClusterDeletion(String project, String databaseId) {
        boolean active = backupRepository.existsByProjectNameAndDatabaseIdAndStatusIn(project, databaseId,
                List.of(BackupStatus.PENDING, BackupStatus.RUNNING, BackupStatus.DELETING));
        if (active) return false;
        return prepareDatabaseDeletion(project, databaseId);
    }

    /** Explicit project purge is opt-in and is used before asynchronous namespace deletion. */
    @Transactional
    public boolean prepareProjectPurge(String project) {
        boolean pending = false;
        for (BackupMetadata backup : backupRepository.findByProjectNameOrderByCreatedAtDesc(project)) {
            if (backup.getStatus() == BackupStatus.PENDING || backup.getStatus() == BackupStatus.RUNNING
                    || backup.getStatus() == BackupStatus.DELETING) {
                pending = true;
                continue;
            }
            if (backup.getStatus() == BackupStatus.COMPLETED || backup.getStatus() == BackupStatus.FAILED) {
                queuePurge(backup, "project-delete:" + project, "Explicit project backup purge requested");
                pending = true;
            }
        }
        return !pending;
    }

    @Transactional
    void queuePurge(BackupMetadata backup, String idempotencyPrefix, String message) {
        if (backup.getStatus() == BackupStatus.DELETING || backup.getStatus() == BackupStatus.DELETED
                || backup.getStatus() == BackupStatus.EXPIRED) return;
        if (restoreRepository.existsByProjectNameAndSourceBackupIdAndStatusIn(backup.getProjectName(),
                backup.getBackupId(), List.of(com.cyfuture.dbaas.model.RestoreStatus.PENDING,
                        com.cyfuture.dbaas.model.RestoreStatus.RUNNING))) {
            return;
        }
        Instant now = Instant.now();
        String operationId = "op-" + shortId();
        backup.setDeleteOperationId(operationId);
        backup.setDeleteIdempotencyKey(idempotencyPrefix + ":" + backup.getBackupId());
        backup.setDeleteRequestHash(idempotencyPrefix);
        backup.setDeletionMode(BackupDeletionMode.PURGE_DATA);
        backup.setStatus(BackupStatus.DELETING);
        backup.setDeleteRequestedAt(now);
        backup.setFailureCode(null);
        backup.setFailureMessage(null);
        backupRepository.save(backup);
        operationRepository.save(OperationMetadata.builder()
                .operationId(operationId)
                .databaseId(backup.getDatabaseId())
                .projectName(backup.getProjectName())
                .type(OperationType.BACKUP_DELETE)
                .status(OperationStatus.PENDING)
                .provisioningStage(ProvisioningStage.QUEUED)
                .progress(0)
                .message(message)
                .idempotencyKey(backup.getDeleteIdempotencyKey())
                .requestHash(idempotencyPrefix)
                .createdAt(now)
                .build());
        String backupId = backup.getBackupId();
        submitAfterCommit(() -> purgeSubmitter.purge(backupId));
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

    private String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
