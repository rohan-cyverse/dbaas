package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.entity.BackupMetadata;
import com.cyfuture.dbaas.model.BackupStatus;
import com.cyfuture.dbaas.repository.BackupMetadataRepository;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/** Deletes only a metadata-proven backup, including its retained data. */
@Service
@RequiredArgsConstructor
public class BackupDeletionSubmitter {
    private final BackupMetadataRepository backupRepository;
    private final DatabaseMetadataRepository databaseRepository;
    private final KubeBlocksClient kubeBlocksClient;

    @Async
    public void delete(String backupId) {
        BackupMetadata backup = backupRepository.findById(backupId).orElse(null);
        if (backup == null || backup.getStatus() != BackupStatus.DELETING) return;
        String namespace = databaseRepository.findByDatabaseIdAndProjectName(
                        backup.getDatabaseId(), backup.getProjectName())
                .map(database -> database.getNamespaceName()).orElse(null);
        if (namespace == null || namespace.isBlank()) {
            fail(backup, "BACKUP_SOURCE_METADATA_MISSING", "Backup source metadata is unavailable.");
            return;
        }
        try {
            kubeBlocksClient.deleteManagedBackup(namespace, backup.getProjectName(), backup.getDatabaseId(),
                    backup.getBackupId(), backup.getOperationId(), backup.getKubernetesBackupName(),
                    backup.getKubernetesUid(), backup.getKubernetesPolicyName());
        } catch (Exception exception) {
            if (!BackupRestoreSafety.retryable(exception)) {
                fail(backup, BackupRestoreSafety.failureCode(exception, "BACKUP_DELETE_FAILED"),
                        BackupRestoreSafety.safeMessage(exception, "Backup deletion failed."));
            }
        }
    }

    private void fail(BackupMetadata backup, String code, String message) {
        backup.setStatus(backup.getCompletedAt() == null ? BackupStatus.FAILED : BackupStatus.COMPLETED);
        backup.setFailureCode(code);
        backup.setFailureMessage(message);
        backupRepository.save(backup);
    }
}
