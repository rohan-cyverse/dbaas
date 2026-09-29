package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.entity.BackupMetadata;
import com.cyfuture.dbaas.entity.BackupPolicyMetadata;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.entity.OperationMetadata;
import com.cyfuture.dbaas.model.BackupStatus;
import com.cyfuture.dbaas.model.BackupTriggerMethod;
import com.cyfuture.dbaas.model.BackupType;
import com.cyfuture.dbaas.model.OperationStatus;
import com.cyfuture.dbaas.model.OperationType;
import com.cyfuture.dbaas.model.ProvisioningStage;
import com.cyfuture.dbaas.repository.BackupMetadataRepository;
import com.cyfuture.dbaas.repository.BackupPolicyMetadataRepository;
import com.cyfuture.dbaas.repository.OperationMetadataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import com.cyfuture.dbaas.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/** Creates the first full backup when automatic backups transition from off to on. */
@Service
@RequiredArgsConstructor
public class ScheduledBackupBootstrapService {
    private final BackupPolicyMetadataRepository policyRepository;
    private final BackupMetadataRepository backupRepository;
    private final OperationMetadataRepository operationRepository;
    private final BackupSubmissionService submissionService;
    private final BackupConfigurationNormalizer normalizer;

    @Transactional
    public boolean ensureInitialFull(BackupPolicyMetadata policy, DatabaseMetadata database,
                                     BackupEngineStrategy strategy) {
        if (!policy.isAutoBackupEnabled() || !policy.isInitialBackupRequired()) return true;

        String token = policy.getPolicyUpdateOperationId() == null
                ? policy.getPolicyId() + "|creation" : policy.getPolicyUpdateOperationId();
        String suffix = shortHash("initial-scheduled|" + token);
        String backupId = "bkp-i-" + suffix;
        BackupMetadata existing = backupRepository.findById(backupId).orElse(null);
        if (existing == null) {
            Instant now = Instant.now();
            String operationId = "op-" + shortHash("operation|" + token);
            BackupMetadata backup = new BackupMetadata();
            backup.setBackupId(backupId);
            backup.setOperationId(operationId);
            backup.setProjectName(policy.getProjectName());
            backup.setDatabaseId(policy.getDatabaseId());
            backup.setEngine(database.getEngine());
            backup.setBackupType(BackupType.FULL);
            backup.setBackupMethod(strategy.manualFullMethod());
            backup.setTriggerMethod(BackupTriggerMethod.SCHEDULED);
            backup.setKubernetesBackupName(backupId);
            backup.setBaseBackupId(backupId);
            backup.setBaseKubernetesBackupName(backupId);
            backup.setStatus(BackupStatus.PENDING);
            backup.setRetentionPeriod(normalizer.duration(policy.getRetentionDays()));
            backup.setIdempotencyKey("scheduled-initial:" + suffix);
            backup.setRequestHash(shortHash("FULL|" + policy.getRetentionDays() + "|" + token));
            backup.setCreatedAt(now);
            backupRepository.save(backup);
            operationRepository.save(OperationMetadata.builder()
                    .operationId(operationId)
                    .databaseId(policy.getDatabaseId())
                    .projectName(policy.getProjectName())
                    .type(OperationType.BACKUP)
                    .status(OperationStatus.PENDING)
                    .provisioningStage(ProvisioningStage.QUEUED)
                    .progress(0)
                    .message("Initial scheduled full backup queued")
                    .idempotencyKey(backup.getIdempotencyKey())
                    .requestHash(backup.getRequestHash())
                    .createdAt(now)
                    .build());
            submitAfterCommit(() -> submissionService.submit(backupId));
        } else if (existing.getStatus() == BackupStatus.FAILED) {
            throw new ApiException(HttpStatus.CONFLICT, "INITIAL_FULL_BACKUP_FAILED", true,
                    "The initial full backup failed. Scheduled backup activation will retry after recovery.");
        }
        policy.setInitialBackupId(backupId);
        if (existing == null || existing.getStatus() != BackupStatus.COMPLETED) return false;
        policy.setInitialBackupRequired(false);
        // Re-apply the policy so its PITR/continuous entry is enabled only
        // after the full base backup is complete.
        policy.setConfigurationApplied(false);
        policyRepository.save(policy);
        return false;
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

    private String shortHash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 20);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
