package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.entity.RestoreRequestMetadata;
import com.cyfuture.dbaas.model.OperationStatus;
import com.cyfuture.dbaas.model.ProvisioningStage;
import com.cyfuture.dbaas.model.RestoreStatus;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import com.cyfuture.dbaas.repository.OperationMetadataRepository;
import com.cyfuture.dbaas.repository.RestoreRequestMetadataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class RestoreSubmissionService {
    private final RestoreRequestMetadataRepository restoreRepository;
    private final DatabaseMetadataRepository databaseRepository;
    private final OperationMetadataRepository operationRepository;
    private final KubeBlocksClient kubeBlocksClient;

    @Async
    public void submit(String restoreId) {
        RestoreRequestMetadata restore = restoreRepository.findById(restoreId).orElse(null);
        if (restore == null || restore.getStatus() == RestoreStatus.COMPLETED
                || restore.getStatus() == RestoreStatus.FAILED) return;
        DatabaseMetadata target = databaseRepository.findByDatabaseIdAndProjectName(
                restore.getRestoredDatabaseId(), restore.getProjectName()).orElse(null);
        if (target == null) {
            fail(restore, "RESTORE_TARGET_METADATA_MISSING", "Restore target metadata is unavailable.");
            return;
        }
        try {
            kubeBlocksClient.createRestoreOpsRequest(target.getNamespaceName(), restore.getProjectName(),
                    target.getDatabaseId(), restore.getKubernetesOpsRequestName(),
                    restore.getSourceBackupId(), restore.getRestoreTime());
            restore.setStatus(RestoreStatus.RUNNING);
            if (restore.getStartedAt() == null) restore.setStartedAt(Instant.now());
            restore.setFailureCode(null);
            restore.setFailureMessage(null);
            restoreRepository.save(restore);
            updateOperation(restore, OperationStatus.RUNNING, ProvisioningStage.RESTORING_DATA,
                    20, "KubeBlocks Restore OpsRequest accepted", false);
        } catch (Exception exception) {
            if (BackupRestoreSafety.retryable(exception)) {
                restore.setStatus(RestoreStatus.PENDING);
                restore.setFailureCode(BackupRestoreSafety.failureCode(exception, "RESTORE_SUBMISSION_RETRY"));
                restore.setFailureMessage(BackupRestoreSafety.safeMessage(exception,
                        "Restore submission will retry when Kubernetes is available."));
                restoreRepository.save(restore);
            } else {
                fail(restore, BackupRestoreSafety.failureCode(exception, "RESTORE_SUBMISSION_FAILED"),
                        BackupRestoreSafety.safeMessage(exception, "Restore submission failed."));
            }
        }
    }

    private void fail(RestoreRequestMetadata restore, String code, String message) {
        restore.setStatus(RestoreStatus.FAILED);
        restore.setFailureCode(code);
        restore.setFailureMessage(message);
        restore.setCompletedAt(Instant.now());
        restoreRepository.save(restore);
        databaseRepository.findByDatabaseIdAndProjectName(restore.getRestoredDatabaseId(), restore.getProjectName())
                .ifPresent(target -> {
                    target.setStatus(com.cyfuture.dbaas.model.DatabaseStatus.FAILED);
                    target.setProvisioningStage(ProvisioningStage.FAILED);
                    target.setProgress(100);
                    target.setMessage("Restore failed.");
                    target.setUpdatedAt(Instant.now());
                    databaseRepository.save(target);
                });
        updateOperation(restore, OperationStatus.FAILED, ProvisioningStage.FAILED, 100,
                "Restore failed.", true);
    }

    private void updateOperation(RestoreRequestMetadata restore, OperationStatus status,
                                 ProvisioningStage stage, int progress, String message,
                                 boolean completed) {
        operationRepository.findById(restore.getOperationId()).ifPresent(operation -> {
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
