package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.config.DatabaseProperties;
import com.cyfuture.dbaas.entity.BackupMetadata;
import com.cyfuture.dbaas.entity.BackupPolicyMetadata;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.entity.OperationMetadata;
import com.cyfuture.dbaas.model.BackupStatus;
import com.cyfuture.dbaas.model.BackupPolicyStatus;
import com.cyfuture.dbaas.model.BackupRetentionPolicy;
import com.cyfuture.dbaas.model.BackupTriggerMethod;
import com.cyfuture.dbaas.model.PitrStatus;
import com.cyfuture.dbaas.model.OperationStatus;
import com.cyfuture.dbaas.model.ProvisioningStage;
import com.cyfuture.dbaas.repository.BackupMetadataRepository;
import com.cyfuture.dbaas.repository.BackupPolicyMetadataRepository;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import com.cyfuture.dbaas.repository.OperationMetadataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class BackupSubmissionService {
    private final BackupMetadataRepository backupRepository;
    private final BackupPolicyMetadataRepository policyRepository;
    private final DatabaseMetadataRepository databaseRepository;
    private final OperationMetadataRepository operationRepository;
    private final KubeBlocksClient kubeBlocksClient;
    private final BackupEngineStrategies strategies;
    private final DatabaseProperties properties;

    @Async
    public void submit(String backupId) {
        BackupMetadata backup = backupRepository.findById(backupId).orElse(null);
        if (backup == null || backup.getStatus() == BackupStatus.DELETING
                || backup.getStatus() == BackupStatus.DELETED || backup.getStatus() == BackupStatus.COMPLETED
                || backup.getTriggerMethod() == BackupTriggerMethod.AUTOMATIC) return;
        DatabaseMetadata source = databaseRepository
                .findByDatabaseIdAndProjectName(backup.getDatabaseId(), backup.getProjectName()).orElse(null);
        if (source == null) {
            fail(backup, "BACKUP_SOURCE_METADATA_MISSING", "Backup source metadata is unavailable.");
            return;
        }
        try {
            BackupEngineStrategy strategy = strategies.require(backup.getEngine());
            String requestedMethod = backup.getBackupMethod() == null || backup.getBackupMethod().isBlank()
                    ? strategy.manualFullMethod() : backup.getBackupMethod();
            KubeBlocksClient.BackupPolicyInfo policy = kubeBlocksClient.resolveReadyBackupPolicy(
                    source.getNamespaceName(), source.getDatabaseId(), source.getEngine(),
                    requestedMethod, backup.getBackupRepositoryName());
            synchronizePolicy(backup, policy);
            kubeBlocksClient.createBackup(source.getNamespaceName(), backup.getProjectName(),
                    backup.getDatabaseId(), backup.getKubernetesBackupName(), policy.policyName(),
                    policy.backupMethod(), backup.getRetentionPeriod(), null,
                    backup.getBackupId(), backup.getOperationId());
            backup.setKubernetesPolicyName(policy.policyName());
            backup.setKubernetesNamespace(source.getNamespaceName());
            backup.setBackupMethod(policy.backupMethod());
            backup.setStatus(BackupStatus.RUNNING);
            if (backup.getStartedAt() == null) backup.setStartedAt(Instant.now());
            backup.setFailureCode(null);
            backup.setFailureMessage(null);
            backupRepository.save(backup);
            updateOperation(backup.getOperationId(), OperationStatus.RUNNING,
                    ProvisioningStage.WAITING_FOR_REPLICAS, 15,
                    "KubeBlocks Backup resource accepted", false);
        } catch (Exception exception) {
            if (BackupRestoreSafety.retryable(exception)) {
                pending(backup, BackupRestoreSafety.failureCode(exception, "BACKUP_SUBMISSION_RETRY"),
                        BackupRestoreSafety.safeMessage(exception,
                                "Backup submission will retry when Kubernetes is available."));
            } else {
                fail(backup, BackupRestoreSafety.failureCode(exception, "BACKUP_SUBMISSION_FAILED"),
                        BackupRestoreSafety.safeMessage(exception, "Backup submission failed."));
            }
        }
    }

    private void synchronizePolicy(BackupMetadata backup, KubeBlocksClient.BackupPolicyInfo observed) {
        BackupPolicyMetadata policy = policyRepository
                .findByProjectNameAndDatabaseId(backup.getProjectName(), backup.getDatabaseId())
                .orElseGet(BackupPolicyMetadata::new);
        boolean newRecord = policy.getPolicyId() == null;
        if (newRecord) {
            policy.setPolicyId("bpol-" + backup.getDatabaseId().substring(3));
            policy.setProjectName(backup.getProjectName());
            policy.setDatabaseId(backup.getDatabaseId());
            policy.setCreatedAt(Instant.now());
            policy.setSchedulingEnabled(false);
            policy.setAutoBackupEnabled(false);
            policy.setRetentionDays(7);
            policy.setRetentionPolicy(BackupRetentionPolicy.RETAIN_ALL);
            policy.setTimezone("UTC");
            policy.setPitrEnabled(false);
            policy.setPitrStatus(PitrStatus.DISABLED);
            policy.setPitrMessage("Point-in-time recovery is disabled.");
            policy.setPolicyStatus(BackupPolicyStatus.ACTIVE);
        }
        policy.setEngine(observed.engine());
        policy.setKubernetesPolicyName(observed.policyName());
        policy.setBackupRepositoryName(observed.repositoryName());
        policy.setDefaultBackupMethod(observed.backupMethod());
        if (observed.continuousMethod() != null && !observed.continuousMethod().isBlank()) {
            policy.setContinuousBackupMethod(observed.continuousMethod());
        }
        policy.setEncryptionConfigured(observed.encryptionConfigured());
        if (policy.getDefaultRetentionPeriod() == null || policy.getDefaultRetentionPeriod().isBlank()) {
            policy.setDefaultRetentionPeriod(properties.getBackup().getDefaultRetention());
        }
        policy.setObservedStatus(observed.observedStatus());
        policy.setLastObservedAt(Instant.now());
        policy.setUpdatedAt(Instant.now());
        policyRepository.save(policy);
    }

    private void pending(BackupMetadata backup, String code, String message) {
        backup.setStatus(BackupStatus.PENDING);
        backup.setFailureCode(code);
        backup.setFailureMessage(message);
        backupRepository.save(backup);
        updateOperation(backup.getOperationId(), OperationStatus.PENDING, ProvisioningStage.QUEUED,
                0, "Backup submission will retry.", false);
    }

    private void fail(BackupMetadata backup, String code, String message) {
        backup.setStatus(BackupStatus.FAILED);
        backup.setFailureCode(code);
        backup.setFailureMessage(message);
        if (backup.getCompletedAt() == null) backup.setCompletedAt(Instant.now());
        backupRepository.save(backup);
        updateOperation(backup.getOperationId(), OperationStatus.FAILED, ProvisioningStage.FAILED,
                100, "Backup failed.", true);
    }

    private void updateOperation(String operationId, OperationStatus status,
                                 ProvisioningStage stage, int progress, String message,
                                 boolean completed) {
        operationRepository.findById(operationId).ifPresent(operation -> {
            operation.setStatus(status);
            operation.setProvisioningStage(stage);
            operation.setProgress(progress);
            operation.setMessage(message);
            if (operation.getStartedAt() == null) operation.setStartedAt(Instant.now());
            if (completed) operation.setCompletedAt(Instant.now());
            operationRepository.save(operation);
        });
    }
}
