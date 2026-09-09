package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.entity.BackupPolicyMetadata;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.entity.OperationMetadata;
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

/** Applies desired Cluster.spec.backup configuration; generated child CRs remain KubeBlocks-owned. */
@Service
@RequiredArgsConstructor
public class BackupPolicySubmissionService {
    private final BackupPolicyMetadataRepository policyRepository;
    private final DatabaseMetadataRepository databaseRepository;
    private final OperationMetadataRepository operationRepository;
    private final KubeBlocksClient kubeBlocksClient;

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
            boolean newlyApplied = !policy.isConfigurationApplied();
            kubeBlocksClient.configureScheduledBackup(database.getNamespaceName(), policy.getProjectName(),
                    database.getDatabaseId(), policy.getDefaultBackupMethod(),
                    policy.getContinuousBackupMethod(), policy.getBackupRepositoryName(),
                    policy.getDefaultRetentionPeriod(), policy.getCronExpression(), policy.isAutoBackupEnabled(),
                    policy.isPitrEnabled());
            if (newlyApplied) {
                policy.setConfigurationApplied(true);
                policy.setLastObservedAt(Instant.now());
                policy.setUpdatedAt(Instant.now());
                policyRepository.save(policy);
                updateOperation(policy, OperationStatus.RUNNING, ProvisioningStage.WAITING_FOR_REPLICAS, 25,
                        "Cluster backup configuration accepted", false);
            }
        } catch (Exception exception) {
            if (BackupRestoreSafety.retryable(exception)) {
                pending(policy, BackupRestoreSafety.failureCode(exception, "BACKUP_POLICY_SUBMISSION_RETRY"),
                        BackupRestoreSafety.safeMessage(exception,
                                "Backup policy submission will retry when Kubernetes is available."));
            } else {
                fail(policy, BackupRestoreSafety.failureCode(exception, "BACKUP_POLICY_SUBMISSION_FAILED"),
                        BackupRestoreSafety.safeMessage(exception, "Backup policy submission failed."));
            }
        }
    }

    private void pending(BackupPolicyMetadata policy, String code, String message) {
        if (policy.getPolicyStatus() != BackupPolicyStatus.PENDING
                || !code.equals(policy.getFailureCode()) || !message.equals(policy.getFailureMessage())) {
            policy.setPolicyStatus(BackupPolicyStatus.PENDING);
            policy.setFailureCode(code);
            policy.setFailureMessage(message);
            policy.setUpdatedAt(Instant.now());
            policyRepository.save(policy);
        }
        updateOperation(policy, OperationStatus.PENDING, ProvisioningStage.QUEUED, 0,
                "Backup policy submission will retry.", false);
    }

    private void fail(BackupPolicyMetadata policy, String code, String message) {
        policy.setPolicyStatus(BackupPolicyStatus.FAILED);
        policy.setFailureCode(code);
        policy.setFailureMessage(message);
        policy.setUpdatedAt(Instant.now());
        policyRepository.save(policy);
        updateOperation(policy, OperationStatus.FAILED, ProvisioningStage.FAILED, 100,
                "Backup policy update failed.", true);
    }

    private void updateOperation(BackupPolicyMetadata policy, OperationStatus status,
                                 ProvisioningStage stage, int progress, String message, boolean completed) {
        if (policy.getPolicyUpdateOperationId() == null) return;
        operationRepository.findById(policy.getPolicyUpdateOperationId()).ifPresent(operation -> {
            if (operation.getStatus() == status && operation.getProgress() == progress
                    && message.equals(operation.getMessage())) return;
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
