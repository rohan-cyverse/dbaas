package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.entity.BackupMetadata;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
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
        DatabaseMetadata source = databaseRepository
                .findByDatabaseIdAndProjectName(backup.getDatabaseId(), backup.getProjectName()).orElse(null);
        if (source == null) {
            fail(backup, "BACKUP_SOURCE_METADATA_MISSING", "Backup source metadata is unavailable for purge.");
            return;
        }
        try {
            kubeBlocksClient.deleteBackup(source.getNamespaceName(), backup.getKubernetesBackupName());
            operationRepository.findById(backup.getDeleteOperationId()).ifPresent(operation -> {
                operation.setStatus(OperationStatus.RUNNING);
                operation.setProvisioningStage(ProvisioningStage.WAITING_FOR_REPLICAS);
                operation.setProgress(25);
                operation.setMessage("KubeBlocks is purging backup data");
                if (operation.getStartedAt() == null) operation.setStartedAt(Instant.now());
                operationRepository.save(operation);
            });
        } catch (Exception exception) {
            if (!BackupRestoreSafety.retryable(exception)) {
                fail(backup, BackupRestoreSafety.failureCode(exception, "BACKUP_PURGE_FAILED"),
                        BackupRestoreSafety.safeMessage(exception, "Backup purge failed."));
            }
        }
    }

    private void fail(BackupMetadata backup, String code, String message) {
        backup.setStatus(BackupStatus.FAILED);
        backup.setFailureCode(code);
        backup.setFailureMessage(message);
        backupRepository.save(backup);
        operationRepository.findById(backup.getDeleteOperationId()).ifPresent(operation -> {
            operation.setStatus(OperationStatus.FAILED);
            operation.setProvisioningStage(ProvisioningStage.FAILED);
            operation.setProgress(100);
            operation.setMessage("Backup purge failed.");
            operation.setCompletedAt(Instant.now());
            operationRepository.save(operation);
        });
    }
}
