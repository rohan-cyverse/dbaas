package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.entity.BackupMetadata;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.model.BackupDeletionMode;
import com.cyfuture.dbaas.model.BackupStatus;
import com.cyfuture.dbaas.model.OperationStatus;
import com.cyfuture.dbaas.model.ProvisioningStage;
import com.cyfuture.dbaas.repository.BackupMetadataRepository;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import com.cyfuture.dbaas.repository.OperationMetadataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;

/** Submits only metadata-proven deletion work; it never removes unknown Backup CRs. */
@Service
@RequiredArgsConstructor
public class BackupPurgeSubmitter {
    private final BackupMetadataRepository backupRepository;
    private final DatabaseMetadataRepository databaseRepository;
    private final OperationMetadataRepository operationRepository;
    private final KubeBlocksClient kubeBlocksClient;

    @Async
    public void purge(String backupId) {
        BackupMetadata backup = backupRepository.findById(backupId).orElse(null);
        if (backup == null || backup.getStatus() != BackupStatus.DELETING) return;
        String namespace = backup.getKubernetesNamespace();
        if (namespace == null || namespace.isBlank()) {
            namespace = databaseRepository.findByDatabaseIdAndProjectName(
                    backup.getDatabaseId(), backup.getProjectName())
                    .map(DatabaseMetadata::getNamespaceName).orElse(null);
        }
        if (namespace == null || namespace.isBlank()) {
            fail(backup, "BACKUP_NAMESPACE_UNAVAILABLE",
                    "Backup namespace is unavailable for deletion.");
            return;
        }
        try {
            kubeBlocksClient.deleteManagedBackup(namespace, backup.getProjectName(), backup.getDatabaseId(),
                    backup.getBackupId(), backup.getOperationId(), backup.getKubernetesBackupName(),
                    backup.getKubernetesUid(), backup.getKubernetesPolicyName(),
                    backup.getDeletionMode() == BackupDeletionMode.PURGE_DATA);
            operationRepository.findById(backup.getDeleteOperationId()).ifPresent(operation -> {
                operation.setStatus(OperationStatus.RUNNING);
                operation.setProvisioningStage(ProvisioningStage.WAITING_FOR_REPLICAS);
                operation.setProgress(25);
                operation.setMessage(backup.getDeletionMode() == BackupDeletionMode.PURGE_DATA
                        ? "KubeBlocks is purging backup data" : "KubeBlocks is deleting the Backup CR");
                if (operation.getStartedAt() == null) operation.setStartedAt(Instant.now());
                operationRepository.save(operation);
            });
        } catch (Exception exception) {
            if (!BackupRestoreSafety.retryable(exception)) {
                fail(backup, BackupRestoreSafety.failureCode(exception, "BACKUP_PURGE_FAILED"),
                        BackupRestoreSafety.safeMessage(exception, "Backup deletion failed."));
            }
        }
    }

    private void fail(BackupMetadata backup, String code, String message) {
        // A failed delete must never turn a successfully completed backup into
        // a failed backup; it remains available for restore and can be retried.
        if (backup.getCompletedAt() != null) {
            backup.setStatus(BackupStatus.COMPLETED);
        } else {
            backup.setStatus(BackupStatus.FAILED);
        }
        backup.setFailureCode(code);
        backup.setFailureMessage(message);
        backupRepository.save(backup);
        operationRepository.findById(backup.getDeleteOperationId()).ifPresent(operation -> {
            operation.setStatus(OperationStatus.FAILED);
            operation.setProvisioningStage(ProvisioningStage.FAILED);
            operation.setProgress(100);
            operation.setMessage("Backup deletion failed.");
            operation.setCompletedAt(Instant.now());
            operationRepository.save(operation);
        });
    }
}
