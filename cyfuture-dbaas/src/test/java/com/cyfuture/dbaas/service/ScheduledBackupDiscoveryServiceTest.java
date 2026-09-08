package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.entity.BackupMetadata;
import com.cyfuture.dbaas.entity.BackupPolicyMetadata;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.model.BackupRetentionPolicy;
import com.cyfuture.dbaas.model.BackupStatus;
import com.cyfuture.dbaas.model.BackupTriggerMethod;
import com.cyfuture.dbaas.model.DatabaseEngine;
import com.cyfuture.dbaas.model.DatabaseMode;
import com.cyfuture.dbaas.model.SizePlan;
import com.cyfuture.dbaas.repository.BackupMetadataRepository;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import com.cyfuture.dbaas.repository.OperationMetadataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduledBackupDiscoveryServiceTest {
    private BackupMetadataRepository backups;
    private BackupRetentionService retention;
    private ScheduledBackupDiscoveryService service;
    private BackupPolicyMetadata policy;
    private DatabaseMetadata source;

    @BeforeEach
    void setUp() {
        backups = mock(BackupMetadataRepository.class);
        retention = mock(BackupRetentionService.class);
        service = new ScheduledBackupDiscoveryService(backups, mock(DatabaseMetadataRepository.class),
                mock(OperationMetadataRepository.class), mock(KubeBlocksClient.class), retention);
        policy = new BackupPolicyMetadata();
        policy.setProjectName("prj-orders");
        policy.setDatabaseId("db-orders");
        policy.setKubernetesPolicyName("db-orders-policy");
        policy.setBackupRepositoryName("cyfuture-dbaas-backuprepo");
        policy.setDefaultRetentionPeriod("7d");
        policy.setRetentionPolicy(BackupRetentionPolicy.RETAIN_LATEST);
        source = source();
    }

    @Test
    void importsCompletedKubeBlocksScheduledBackupIntoTheSameBackupHistory() {
        KubeBlocksClient.ScheduledBackupInfo scheduled = new KubeBlocksClient.ScheduledBackupInfo(
                "scheduled-orders-001", "uid-scheduled-001", "pg-basebackup", "7d", "Completed",
                "done", 42L, Instant.parse("2026-09-08T10:00:00Z"),
                Instant.parse("2026-09-08T10:01:00Z"));
        when(backups.findByKubernetesNamespaceAndKubernetesBackupName(
                "dbaas-orders", "scheduled-orders-001")).thenReturn(Optional.empty());

        service.importOne(policy, source, scheduled);

        ArgumentCaptor<BackupMetadata> captured = ArgumentCaptor.forClass(BackupMetadata.class);
        verify(backups).save(captured.capture());
        BackupMetadata imported = captured.getValue();
        assertEquals(BackupTriggerMethod.AUTOMATIC, imported.getTriggerMethod());
        assertEquals(BackupStatus.COMPLETED, imported.getStatus());
        assertEquals("pg-basebackup", imported.getBackupMethod());
        assertEquals("scheduled-orders-001", imported.getKubernetesBackupName());
        assertEquals("dbaas-orders", imported.getKubernetesNamespace());
        assertEquals(42L, imported.getSizeBytes());
        verify(retention).recordCompletion(imported.getBackupId());
    }

    @Test
    void failedScheduledBackupDoesNotReplaceTheLatestSuccessfulRecoveryPoint() {
        KubeBlocksClient.ScheduledBackupInfo failed = new KubeBlocksClient.ScheduledBackupInfo(
                "scheduled-orders-002", "uid-scheduled-002", "pg-basebackup", "7d", "Failed",
                "worker failed", null, Instant.parse("2026-09-08T10:00:00Z"),
                Instant.parse("2026-09-08T10:01:00Z"));
        when(backups.findByKubernetesNamespaceAndKubernetesBackupName(
                "dbaas-orders", "scheduled-orders-002")).thenReturn(Optional.empty());

        service.importOne(policy, source, failed);

        ArgumentCaptor<BackupMetadata> captured = ArgumentCaptor.forClass(BackupMetadata.class);
        verify(backups).save(captured.capture());
        assertEquals(BackupStatus.FAILED, captured.getValue().getStatus());
        verify(retention, never()).recordCompletion(captured.getValue().getBackupId());
    }

    private DatabaseMetadata source() {
        DatabaseMetadata database = new DatabaseMetadata();
        database.setProjectName("prj-orders");
        database.setDatabaseId("db-orders");
        database.setDisplayName("orders-db");
        database.setNamespaceName("dbaas-orders");
        database.setEngine(DatabaseEngine.POSTGRESQL);
        database.setMode(DatabaseMode.REPLICATION);
        database.setDatabaseVersion("17.5.0");
        database.setSizePlan(SizePlan.C1G1);
        database.setStorageGi(10);
        database.setReplicas(2);
        database.setShards(0);
        return database;
    }
}
