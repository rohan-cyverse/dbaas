package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.model.RestoreStatus;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import com.cyfuture.dbaas.repository.RestoreRequestMetadataRepository;
import com.cyfuture.dbaas.client.KubeBlocksClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class TemporaryRestoreCleanupService {
    private final RestoreRequestMetadataRepository restoreRepository;
    private final RestoreService restoreService;
    private final DatabaseMetadataRepository databaseRepository;
    private final KubeBlocksClient kubeBlocksClient;

    @Scheduled(fixedDelayString = "${dbaas.restore.cleanup-ms:300000}")
    public void cleanupExpiredTemporaryRestores() {
        for (var restore : restoreRepository
                .findByTemporaryTrueAndPromotedAtIsNullAndDeletedAtIsNullAndExpiresAtBeforeAndStatusInOrderByExpiresAtAsc(
                        Instant.now(), List.of(RestoreStatus.PENDING, RestoreStatus.SAFETY_BACKUP,
                                RestoreStatus.RESTORING, RestoreStatus.VALIDATING, RestoreStatus.CUTTING_OVER,
                                RestoreStatus.ROLLING_BACK, RestoreStatus.RUNNING,
                                RestoreStatus.READY, RestoreStatus.COMPLETED, RestoreStatus.FAILED))) {
            try {
                restoreService.expireTemporaryRestore(restore);
            } catch (Exception exception) {
                log.debug("Expired temporary restore cleanup for {} will retry: {}",
                        restore.getRestoreId(),
                        BackupRestoreSafety.safeMessage(exception, "Temporary restore cleanup will retry."));
            }
        }
        cleanupOldClusters();
    }

    private void cleanupOldClusters() {
        for (var restore : restoreRepository
                .findByOldClusterDeleteAtBeforeAndOldClusterDeletedAtIsNullAndStatus(
                        Instant.now(), RestoreStatus.COMPLETED)) {
            try {
                databaseRepository.findByDatabaseIdAndProjectName(
                        restore.getRestoredDatabaseId(), restore.getProjectName()).ifPresent(database -> {
                    if (restore.getOldClusterName() == null || restore.getOldClusterName().isBlank()) return;
                    if (restore.getOldClusterName().equals(database.physicalClusterName())) return;
                    kubeBlocksClient.requestDelete(database.getNamespaceName(), restore.getOldClusterName());
                    var observed = kubeBlocksClient.observeCluster(database.getNamespaceName(), restore.getOldClusterName());
                    if (!observed.exists()) {
                        restore.setOldClusterDeletedAt(Instant.now());
                        restoreRepository.save(restore);
                    }
                });
            } catch (Exception exception) {
                log.debug("Rollback-cluster cleanup for {} will retry: {}",
                        restore.getRestoreId(),
                        BackupRestoreSafety.safeMessage(exception, "Old restore cluster cleanup will retry."));
            }
        }
    }
}
