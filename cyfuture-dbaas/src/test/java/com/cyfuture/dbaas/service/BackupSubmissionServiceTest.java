package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.config.DatabaseProperties;
import com.cyfuture.dbaas.entity.BackupMetadata;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.model.BackupStatus;
import com.cyfuture.dbaas.model.DatabaseEngine;
import com.cyfuture.dbaas.model.DatabaseMode;
import com.cyfuture.dbaas.repository.BackupMetadataRepository;
import com.cyfuture.dbaas.repository.BackupPolicyMetadataRepository;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import com.cyfuture.dbaas.repository.OperationMetadataRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackupSubmissionServiceTest {
    @ParameterizedTest
    @MethodSource("engines")
    void submitsTheInstalledFullBackupMethodForEachSupportedEngine(DatabaseEngine engine,
                                                                    DatabaseMode mode,
                                                                    String method) {
        BackupMetadataRepository backups = mock(BackupMetadataRepository.class);
        BackupPolicyMetadataRepository policies = mock(BackupPolicyMetadataRepository.class);
        DatabaseMetadataRepository databases = mock(DatabaseMetadataRepository.class);
        KubeBlocksClient kubeBlocks = mock(KubeBlocksClient.class);
        BackupSubmissionService service = new BackupSubmissionService(backups, policies, databases,
                mock(OperationMetadataRepository.class), kubeBlocks,
                strategies(), new DatabaseProperties());
        BackupMetadata backup = backup(engine);
        DatabaseMetadata source = source(engine, mode);
        when(backups.findById(backup.getBackupId())).thenReturn(Optional.of(backup));
        when(databases.findByDatabaseIdAndProjectName(backup.getDatabaseId(), backup.getProjectName()))
                .thenReturn(Optional.of(source));
        when(policies.findByProjectNameAndDatabaseId(backup.getProjectName(), backup.getDatabaseId()))
                .thenReturn(Optional.empty());
        when(kubeBlocks.resolveReadyBackupPolicy(source.getNamespaceName(), source.getDatabaseId(), engine,
                method, "cyfuture-dbaas-backuprepo"))
                .thenReturn(new KubeBlocksClient.BackupPolicyInfo("generated-policy",
                        "cyfuture-dbaas-backuprepo", method, false, "AVAILABLE", engine));

        service.submit(backup.getBackupId());

        verify(kubeBlocks).createBackup(eq("dbaas-orders"), eq("prj-orders"),
                eq("db-orders"), eq("bkp-orders"), eq("generated-policy"), eq(method),
                eq("7d"), eq(null), eq("bkp-orders"), eq("op-backup"));
        assertEquals(BackupStatus.RUNNING, backup.getStatus());
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> engines() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(DatabaseEngine.POSTGRESQL,
                        DatabaseMode.REPLICATION, "pg-basebackup"),
                org.junit.jupiter.params.provider.Arguments.of(DatabaseEngine.MYSQL,
                        DatabaseMode.REPLICATION, "xtrabackup"),
                org.junit.jupiter.params.provider.Arguments.of(DatabaseEngine.MONGODB,
                        DatabaseMode.REPLICA_SET, "dump"));
    }

    private BackupEngineStrategies strategies() {
        return new BackupEngineStrategies(List.of(new PostgreSqlBackupEngineStrategy(),
                new MySqlBackupEngineStrategy(), new MongoDbBackupEngineStrategy()));
    }

    private BackupMetadata backup(DatabaseEngine engine) {
        BackupMetadata backup = new BackupMetadata();
        backup.setBackupId("bkp-orders");
        backup.setOperationId("op-backup");
        backup.setProjectName("prj-orders");
        backup.setDatabaseId("db-orders");
        backup.setEngine(engine);
        backup.setBackupMethod(switch (engine) {
            case POSTGRESQL -> "pg-basebackup";
            case MYSQL -> "xtrabackup";
            case MONGODB -> "dump";
        });
        backup.setKubernetesBackupName("bkp-orders");
        backup.setBackupRepositoryName("cyfuture-dbaas-backuprepo");
        backup.setRetentionPeriod("7d");
        backup.setStatus(BackupStatus.PENDING);
        return backup;
    }

    private DatabaseMetadata source(DatabaseEngine engine, DatabaseMode mode) {
        DatabaseMetadata source = new DatabaseMetadata();
        source.setProjectName("prj-orders");
        source.setDatabaseId("db-orders");
        source.setNamespaceName("dbaas-orders");
        source.setEngine(engine);
        source.setMode(mode);
        return source;
    }
}
