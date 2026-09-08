package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.entity.BackupMetadata;
import com.cyfuture.dbaas.model.BackupStatus;
import com.cyfuture.dbaas.repository.BackupMetadataRepository;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import com.cyfuture.dbaas.repository.OperationMetadataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackupReconcilerTest {
    private BackupMetadataRepository backups;
    private KubeBlocksClient kubeBlocks;
    private BackupPurgeSubmitter purgeSubmitter;
    private BackupReconciler reconciler;

    @BeforeEach
    void setUp() {
        backups = mock(BackupMetadataRepository.class);
        kubeBlocks = mock(KubeBlocksClient.class);
        purgeSubmitter = mock(BackupPurgeSubmitter.class);
        reconciler = new BackupReconciler(backups, mock(DatabaseMetadataRepository.class),
                mock(OperationMetadataRepository.class), kubeBlocks, mock(BackupSubmissionService.class),
                purgeSubmitter, mock(BackupRetentionService.class));
    }

    @Test
    void observedKubeBlocksScheduleDeletionNeverTriggersAnExtraDelete() {
        BackupMetadata automatic = deletingBackup(null);
        when(backups.findByStatusInOrderByCreatedAtAsc(any())).thenReturn(List.of(automatic));
        when(kubeBlocks.observeManagedBackup("dbaas-orders", "prj-orders", "db-orders",
                "bkp-scheduled-001", null, "scheduled-orders-001", null, null))
                .thenReturn(new KubeBlocksClient.BackupObservation(true, "Deleting", "expiring", 10L,
                        Instant.parse("2026-09-08T10:00:00Z"), null, "uid-1",
                        Instant.parse("2026-09-15T10:00:00Z")));

        reconciler.reconcile();

        verify(purgeSubmitter, never()).purge(any());
    }

    @Test
    void explicitlyQueuedDeletionIsRestartSafeAndResubmitted() {
        BackupMetadata explicitDelete = deletingBackup("op-delete-001");
        when(backups.findByStatusInOrderByCreatedAtAsc(any())).thenReturn(List.of(explicitDelete));
        when(kubeBlocks.observeManagedBackup("dbaas-orders", "prj-orders", "db-orders",
                "bkp-scheduled-001", null, "scheduled-orders-001", null, null))
                .thenReturn(new KubeBlocksClient.BackupObservation(true, "Deleting", "deleting", null,
                        null, null, "uid-1", null));

        reconciler.reconcile();

        verify(purgeSubmitter).purge("bkp-scheduled-001");
    }

    private BackupMetadata deletingBackup(String deleteOperationId) {
        BackupMetadata backup = new BackupMetadata();
        backup.setBackupId("bkp-scheduled-001");
        backup.setProjectName("prj-orders");
        backup.setDatabaseId("db-orders");
        backup.setKubernetesNamespace("dbaas-orders");
        backup.setKubernetesBackupName("scheduled-orders-001");
        backup.setStatus(BackupStatus.DELETING);
        backup.setDeleteOperationId(deleteOperationId);
        return backup;
    }
}
