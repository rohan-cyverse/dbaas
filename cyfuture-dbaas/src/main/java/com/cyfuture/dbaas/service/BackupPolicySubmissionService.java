package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.entity.BackupPolicyMetadata;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.model.BackupPolicyStatus;
import com.cyfuture.dbaas.model.OperationStatus;
import com.cyfuture.dbaas.model.ProvisioningStage;
import com.cyfuture.dbaas.repository.BackupPolicyMetadataRepository;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import com.cyfuture.dbaas.repository.OperationMetadataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;

/** Applies only Cluster backup settings. The global BackupRepo is only read and referenced. */
@Service
@RequiredArgsConstructor
public class BackupPolicySubmissionService {
    private final BackupPolicyMetadataRepository policyRepository;
    private final DatabaseMetadataRepository databaseRepository;
    private final OperationMetadataRepository operationRepository;
    private final KubeBlocksClient kubeBlocksClient;
    private final BackupEngineStrategies strategies;
    private final BackupConfigurationNormalizer normalizer;

    @Async
    public void submit(String policyId) {
        BackupPolicyMetadata policy = policyRepository.findById(policyId).orElse(null);
        if (policy == null || policy.getPolicyStatus() == BackupPolicyStatus.FAILED) return;
        DatabaseMetadata database = databaseRepository.findByDatabaseIdAndProjectName(
                policy.getDatabaseId(), policy.getProjectName()).orElse(null);
        if (database == null) {
            fail(policy, "BACKUP_SOURCE_METADATA_MISSING", "Backup source metadata is unavailable.");
            return;
        }
        try {
            BackupEngineStrategy strategy = strategies.require(database.getEngine());
            kubeBlocksClient.configureScheduledBackup(database.getNamespaceName(), policy.getProjectName(),
                    database.getDatabaseId(), strategy.manualFullMethod(),
                    strategy.continuousMethod(), normalizer.repositoryName(),
                    normalizer.duration(policy.getRetentionDays()), policy.getCronExpression(),
                    policy.isAutoBackupEnabled(), policy.isPitrEnabled());
            if (!policy.isConfigurationApplied()) {
                policy.setConfigurationApplied(true);
                policy.setLastObservedAt(Instant.now());
                policy.setUpdatedAt(Instant.now());
                policyRepository.save(policy);
                updateOperation(policy, OperationStatus.RUNNING, ProvisioningStage.WAITING_FOR_REPLICAS, 25,
                        "Backup settings accepted", false);
            }
        } catch (Exception exception) {
            if (BackupRestoreSafety.retryable(exception)) {
                pending(policy, BackupRestoreSafety.failureCode(exception, "BACKUP_SETTINGS_SUBMISSION_RETRY"),
                        BackupRestoreSafety.safeMessage(exception,
                                "Backup settings will retry when Kubernetes is available."));
            } else {
                fail(policy, BackupRestoreSafety.failureCode(exception, "BACKUP_SETTINGS_SUBMISSION_FAILED"),
                        BackupRestoreSafety.safeMessage(exception, "Backup settings update failed."));
            }
        }
    }

    private void pending(BackupPolicyMetadata policy, String code, String message) {
        policy.setPolicyStatus(BackupPolicyStatus.PENDING);
        policy.setFailureCode(code);
        policy.setFailureMessage(message);
        policy.setUpdatedAt(Instant.now());
        policyRepository.save(policy);
        updateOperation(policy, OperationStatus.PENDING, ProvisioningStage.QUEUED, 0,
                "Backup settings will retry.", false);
    }

    private void fail(BackupPolicyMetadata policy, String code, String message) {
        policy.setPolicyStatus(BackupPolicyStatus.FAILED);
        policy.setFailureCode(code);
        policy.setFailureMessage(message);
        policy.setUpdatedAt(Instant.now());
        policyRepository.save(policy);
        updateOperation(policy, OperationStatus.FAILED, ProvisioningStage.FAILED, 100,
                "Backup settings update failed.", true);
    }

    private void updateOperation(BackupPolicyMetadata policy, OperationStatus status,
                                 ProvisioningStage stage, int progress, String message, boolean completed) {
        if (policy.getPolicyUpdateOperationId() == null) return;
        operationRepository.findById(policy.getPolicyUpdateOperationId()).ifPresent(operation -> {
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
