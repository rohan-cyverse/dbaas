package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.entity.BackupMetadata;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.model.BackupStatus;
import com.cyfuture.dbaas.repository.BackupMetadataRepository;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackupDeletionSubmitterTest {

    @Test
    void removesMetadataWhenKubernetesConfirmsBackupIsGone() {
        BackupMetadataRepository backups = mock(BackupMetadataRepository.class);
        DatabaseMetadataRepository databases = mock(DatabaseMetadataRepository.class);
        KubeBlocksClient client = mock(KubeBlocksClient.class);
        BackupDeletionSubmitter submitter = new BackupDeletionSubmitter(backups, databases, client);
        BackupMetadata backup = deletingBackup();
        DatabaseMetadata database = new DatabaseMetadata();
        database.setNamespaceName("dbaas-orders");
        when(backups.findById(backup.getBackupId())).thenReturn(Optional.of(backup));
        when(databases.findByDatabaseIdAndProjectName("db-orders0001", "orders"))
                .thenReturn(Optional.of(database));
        when(client.observeManagedBackup("dbaas-orders", "orders", "db-orders0001",
                "bkp-orders0001", "op-orders0001", "kb-orders0001", "uid-orders0001", null))
                .thenReturn(KubeBlocksClient.BackupObservation.missing());

        submitter.delete(backup.getBackupId());

        verify(client).deleteManagedBackup("dbaas-orders", "orders", "db-orders0001",
                "bkp-orders0001", "op-orders0001", "kb-orders0001", "uid-orders0001", null);
        verify(backups).delete(backup);
    }

    @Test
    void keepsMetadataUntilKubernetesFinalizerFinishes() {
        BackupMetadataRepository backups = mock(BackupMetadataRepository.class);
        DatabaseMetadataRepository databases = mock(DatabaseMetadataRepository.class);
        KubeBlocksClient client = mock(KubeBlocksClient.class);
        BackupDeletionSubmitter submitter = new BackupDeletionSubmitter(backups, databases, client);
        BackupMetadata backup = deletingBackup();
        DatabaseMetadata database = new DatabaseMetadata();
        database.setNamespaceName("dbaas-orders");
        when(backups.findById(backup.getBackupId())).thenReturn(Optional.of(backup));
        when(databases.findByDatabaseIdAndProjectName("db-orders0001", "orders"))
                .thenReturn(Optional.of(database));
        when(client.observeManagedBackup("dbaas-orders", "orders", "db-orders0001",
                "bkp-orders0001", "op-orders0001", "kb-orders0001", "uid-orders0001", null))
                .thenReturn(new KubeBlocksClient.BackupObservation(true, "Completed", null,
                        null, null, null, "uid-orders0001", null, null, null, null, null));

        submitter.delete(backup.getBackupId());

        verify(backups, never()).delete(backup);
    }

    private BackupMetadata deletingBackup() {
        BackupMetadata backup = new BackupMetadata();
        backup.setBackupId("bkp-orders0001");
        backup.setOperationId("op-orders0001");
        backup.setProjectName("orders");
        backup.setDatabaseId("db-orders0001");
        backup.setKubernetesBackupName("kb-orders0001");
        backup.setKubernetesUid("uid-orders0001");
        backup.setStatus(BackupStatus.DELETING);
        return backup;
    }
}
