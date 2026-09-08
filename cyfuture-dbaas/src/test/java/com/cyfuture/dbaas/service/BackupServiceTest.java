package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.config.DatabaseProperties;
import com.cyfuture.dbaas.dto.CreateBackupRequest;
import com.cyfuture.dbaas.entity.BackupMetadata;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.entity.OperationMetadata;
import com.cyfuture.dbaas.exception.ApiException;
import com.cyfuture.dbaas.model.BackupDeletionMode;
import com.cyfuture.dbaas.model.BackupStatus;
import com.cyfuture.dbaas.model.BackupType;
import com.cyfuture.dbaas.model.DatabaseEngine;
import com.cyfuture.dbaas.model.DatabaseMode;
import com.cyfuture.dbaas.model.DatabaseStatus;
import com.cyfuture.dbaas.model.OperationStatus;
import com.cyfuture.dbaas.repository.BackupMetadataRepository;
import com.cyfuture.dbaas.repository.BackupPolicyMetadataRepository;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import com.cyfuture.dbaas.repository.OperationMetadataRepository;
import com.cyfuture.dbaas.repository.RestoreRequestMetadataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackupServiceTest {
    private DatabaseMetadataRepository databases;
    private BackupMetadataRepository backups;
    private OperationMetadataRepository operations;
    private KubeBlocksClient kubeBlocks;
    private BackupSubmissionService submission;
    private BackupPurgeSubmitter purgeSubmitter;
    private BackupService service;

    @BeforeEach
    void setUp() {
        databases = mock(DatabaseMetadataRepository.class);
        backups = mock(BackupMetadataRepository.class);
        operations = mock(OperationMetadataRepository.class);
        kubeBlocks = mock(KubeBlocksClient.class);
        submission = mock(BackupSubmissionService.class);
        purgeSubmitter = mock(BackupPurgeSubmitter.class);
        service = new BackupService(databases, backups, mock(BackupPolicyMetadataRepository.class),
                mock(RestoreRequestMetadataRepository.class), operations, mock(ProjectService.class),
                new BackupEngineStrategies(List.of(new PostgreSqlBackupEngineStrategy(),
                        new MySqlBackupEngineStrategy(), new MongoDbBackupEngineStrategy())),
                new BackupConfigurationNormalizer(new DatabaseProperties()), kubeBlocks,
                submission, purgeSubmitter);
        when(databases.findByDatabaseIdAndProjectNameForUpdate("db-orders", "prj-orders"))
                .thenReturn(Optional.of(source()));
        when(operations.findByDatabaseIdAndProjectNameAndStatusIn(anyString(), anyString(), any()))
                .thenReturn(List.of());
        when(backups.findByProjectNameAndDatabaseIdAndIdempotencyKey(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
    }

    @Test
    void manualBackupIsIdempotentAndUsesTheExistingReadyRepository() {
        var first = service.create("prj-orders", "db-orders", "backup-orders-001",
                new CreateBackupRequest(BackupType.FULL, 7, null));
        ArgumentCaptor<BackupMetadata> captured = ArgumentCaptor.forClass(BackupMetadata.class);
        verify(backups).save(captured.capture());
        BackupMetadata saved = captured.getValue();
        when(backups.findByProjectNameAndDatabaseIdAndIdempotencyKey(
                "prj-orders", "db-orders", "backup-orders-001")).thenReturn(Optional.of(saved));
        when(operations.findById(saved.getOperationId())).thenReturn(Optional.of(OperationMetadata.builder()
                .operationId(saved.getOperationId()).status(OperationStatus.PENDING).build()));

        var retry = service.create("prj-orders", "db-orders", "backup-orders-001",
                new CreateBackupRequest(BackupType.FULL, 7, null));

        assertEquals(first, retry);
        assertEquals(BackupStatus.PENDING, saved.getStatus());
        assertEquals("pg-basebackup", saved.getBackupMethod());
        assertEquals("7d", saved.getRetentionPeriod());
        assertEquals("cyfuture-dbaas-backuprepo", saved.getBackupRepositoryName());
        verify(kubeBlocks).validateReadyBackupRepository("cyfuture-dbaas-backuprepo");
        verify(submission).submit(saved.getBackupId());
        verify(backups, times(1)).save(any(BackupMetadata.class));
    }

    @Test
    void deletingABackupDefaultsToCrOnlyAndNeverImplicitlyPurgesData() {
        BackupMetadata backup = completedBackup();
        when(backups.findByBackupIdAndProjectNameAndDatabaseId(
                "bkp-orders", "prj-orders", "db-orders")).thenReturn(Optional.of(backup));

        var accepted = service.delete("prj-orders", "db-orders", "bkp-orders",
                "delete-orders-001", false);

        assertEquals(BackupStatus.DELETING, backup.getStatus());
        assertEquals(BackupDeletionMode.CR_ONLY, backup.getDeletionMode());
        assertEquals("bkp-orders", accepted.resourceId());
        verify(purgeSubmitter).purge("bkp-orders");
    }

    @Test
    void incrementalAndContinuousRequestsAreExplicitlyUnavailable() {
        ApiException exception = assertThrows(ApiException.class,
                () -> service.create("prj-orders", "db-orders", "backup-orders-002",
                        new CreateBackupRequest(BackupType.INCREMENTAL, 7, null)));

        assertEquals("FEATURE_NOT_AVAILABLE", exception.getCode());
        verify(kubeBlocks, never()).validateReadyBackupRepository(anyString());
    }

    private DatabaseMetadata source() {
        DatabaseMetadata source = new DatabaseMetadata();
        source.setProjectName("prj-orders");
        source.setDatabaseId("db-orders");
        source.setDisplayName("orders-db");
        source.setNamespaceName("dbaas-orders");
        source.setEngine(DatabaseEngine.POSTGRESQL);
        source.setMode(DatabaseMode.REPLICATION);
        source.setStatus(DatabaseStatus.RUNNING);
        source.setDatabaseVersion("17.5.0");
        source.setSizePlan(com.cyfuture.dbaas.model.SizePlan.C1G1);
        source.setStorageGi(10);
        source.setReplicas(2);
        source.setShards(0);
        return source;
    }

    private BackupMetadata completedBackup() {
        BackupMetadata backup = new BackupMetadata();
        backup.setBackupId("bkp-orders");
        backup.setOperationId("op-backup");
        backup.setProjectName("prj-orders");
        backup.setDatabaseId("db-orders");
        backup.setStatus(BackupStatus.COMPLETED);
        return backup;
    }
}
