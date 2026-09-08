package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.dto.CreateRestoreRequest;
import com.cyfuture.dbaas.entity.BackupMetadata;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.entity.ProjectMetadata;
import com.cyfuture.dbaas.entity.RestoreRequestMetadata;
import com.cyfuture.dbaas.model.BackupStatus;
import com.cyfuture.dbaas.model.BackupType;
import com.cyfuture.dbaas.model.DatabaseEngine;
import com.cyfuture.dbaas.model.DatabaseMode;
import com.cyfuture.dbaas.model.DatabaseStatus;
import com.cyfuture.dbaas.model.SizePlan;
import com.cyfuture.dbaas.repository.BackupMetadataRepository;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import com.cyfuture.dbaas.repository.OperationMetadataRepository;
import com.cyfuture.dbaas.repository.RestoreRequestMetadataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RestoreServiceTest {
    private BackupMetadataRepository backups;
    private DatabaseMetadataRepository databases;
    private RestoreRequestMetadataRepository restores;
    private RestoreSubmissionService submission;
    private RestoreService service;

    @BeforeEach
    void setUp() {
        backups = mock(BackupMetadataRepository.class);
        databases = mock(DatabaseMetadataRepository.class);
        restores = mock(RestoreRequestMetadataRepository.class);
        submission = mock(RestoreSubmissionService.class);
        ProjectService projects = mock(ProjectService.class);
        ProjectMetadata project = new ProjectMetadata();
        project.setNamespaceName("dbaas-orders");
        when(projects.requireActiveProject("prj-orders")).thenReturn(project);
        service = new RestoreService(backups, restores, databases, mock(OperationMetadataRepository.class),
                projects, new BackupEngineStrategies(List.of(new PostgreSqlBackupEngineStrategy(),
                new MySqlBackupEngineStrategy(), new MongoDbBackupEngineStrategy())), submission);
    }

    @Test
    void restoreCreatesNewTargetMetadataAndSubmitsOnlyARestoreOperation() {
        BackupMetadata backup = completedBackup();
        when(backups.findByBackupIdAndProjectNameAndDatabaseId("bkp-orders", "prj-orders", "db-orders"))
                .thenReturn(Optional.of(backup));
        when(restores.findByProjectNameAndSourceBackupIdAndIdempotencyKey(
                "prj-orders", "bkp-orders", "restore-orders-001")).thenReturn(Optional.empty());
        when(databases.existsByProjectNameAndDisplayName("prj-orders", "restored-orders-db"))
                .thenReturn(false);

        var accepted = service.restore("prj-orders", "db-orders", "bkp-orders", "restore-orders-001",
                new CreateRestoreRequest("restored-orders-db", null));

        ArgumentCaptor<DatabaseMetadata> target = ArgumentCaptor.forClass(DatabaseMetadata.class);
        ArgumentCaptor<RestoreRequestMetadata> request = ArgumentCaptor.forClass(RestoreRequestMetadata.class);
        verify(databases).save(target.capture());
        verify(restores).save(request.capture());
        assertEquals(accepted.resourceId(), target.getValue().getDatabaseId());
        assertEquals("restored-orders-db", target.getValue().getDisplayName());
        assertEquals(DatabaseStatus.PROVISIONING, target.getValue().getStatus());
        assertEquals(backup.getEngine(), target.getValue().getEngine());
        assertEquals(backup.getSourceDatabaseVersion(), target.getValue().getDatabaseVersion());
        assertEquals("bkp-orders", request.getValue().getSourceBackupId());
        assertEquals("kb-backup-orders", request.getValue().getSourceKubernetesBackupName());
        assertEquals("orders_data", request.getValue().getRestoredDatabaseName());
        verify(submission).submit(request.getValue().getRestoreId());
    }

    private BackupMetadata completedBackup() {
        BackupMetadata backup = new BackupMetadata();
        backup.setBackupId("bkp-orders");
        backup.setProjectName("prj-orders");
        backup.setDatabaseId("db-orders");
        backup.setEngine(DatabaseEngine.POSTGRESQL);
        backup.setBackupType(BackupType.FULL);
        backup.setBackupMethod("pg-basebackup");
        backup.setStatus(BackupStatus.COMPLETED);
        backup.setKubernetesBackupName("kb-backup-orders");
        backup.setKubernetesNamespace("dbaas-orders");
        backup.setSourceMode(DatabaseMode.REPLICATION);
        backup.setSourceDatabaseVersion("17.5.0");
        backup.setSourceLogicalDatabaseName("orders_data");
        backup.setSourceSizePlan(SizePlan.C1G1);
        backup.setSourceStorageGi(10);
        backup.setSourceReplicas(2);
        backup.setSourceShards(0);
        backup.setSourceTimezone("UTC");
        backup.setSourceAllowedCidrs("[203.0.113.10/32]");
        backup.setSourceTags("env=prod");
        return backup;
    }
}
