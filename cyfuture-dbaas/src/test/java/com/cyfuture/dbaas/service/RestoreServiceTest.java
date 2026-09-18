package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.dto.CreateRestoreRequest;
import com.cyfuture.dbaas.entity.BackupMetadata;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.entity.ProjectMetadata;
import com.cyfuture.dbaas.entity.RestoreRequestMetadata;
import com.cyfuture.dbaas.exception.ApiException;
import com.cyfuture.dbaas.model.BackupStatus;
import com.cyfuture.dbaas.model.BackupTriggerMethod;
import com.cyfuture.dbaas.model.BackupType;
import com.cyfuture.dbaas.model.DatabaseEngine;
import com.cyfuture.dbaas.model.DatabaseMode;
import com.cyfuture.dbaas.model.DatabaseStatus;
import com.cyfuture.dbaas.model.DesiredState;
import com.cyfuture.dbaas.model.ProvisioningStage;
import com.cyfuture.dbaas.model.RestoreAccessMode;
import com.cyfuture.dbaas.model.RestoreMode;
import com.cyfuture.dbaas.model.RestoreStatus;
import com.cyfuture.dbaas.repository.BackupMetadataRepository;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import com.cyfuture.dbaas.repository.OperationMetadataRepository;
import com.cyfuture.dbaas.repository.RestoreRequestMetadataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RestoreServiceTest {
    private BackupMetadataRepository backupRepository;
    private RestoreRequestMetadataRepository restoreRepository;
    private DatabaseMetadataRepository databaseRepository;
    private OperationMetadataRepository operationRepository;
    private ProjectService projectService;
    private BackupEngineStrategies strategies;
    private RestoreSubmissionService submissionService;
    private RestoreReconciler restoreReconciler;
    private PitrRecoveryService pitrRecoveryService;
    private BackupPolicyService backupPolicyService;
    private DatabaseService databaseService;
    private OperationService operationService;
    private RestoreService service;
    private DatabaseMetadata source;
    private BackupMetadata backup;

    @BeforeEach
    void setUp() {
        backupRepository = mock(BackupMetadataRepository.class);
        restoreRepository = mock(RestoreRequestMetadataRepository.class);
        databaseRepository = mock(DatabaseMetadataRepository.class);
        operationRepository = mock(OperationMetadataRepository.class);
        projectService = mock(ProjectService.class);
        strategies = mock(BackupEngineStrategies.class);
        submissionService = mock(RestoreSubmissionService.class);
        restoreReconciler = mock(RestoreReconciler.class);
        pitrRecoveryService = mock(PitrRecoveryService.class);
        backupPolicyService = mock(BackupPolicyService.class);
        databaseService = mock(DatabaseService.class);
        operationService = mock(OperationService.class);
        service = new RestoreService(backupRepository, restoreRepository, databaseRepository,
                operationRepository, projectService, strategies, submissionService, restoreReconciler,
                pitrRecoveryService, backupPolicyService, databaseService, operationService);

        ProjectMetadata project = new ProjectMetadata();
        project.setProjectId("orders");
        project.setNamespaceName("dbaas-orders");
        when(projectService.requireActiveProject("orders")).thenReturn(project);

        source = database("db-source0001", "orders");
        when(databaseRepository.findByDatabaseIdAndProjectNameForUpdate("db-source0001", "orders"))
                .thenReturn(Optional.of(source));

        backup = backup(BackupStatus.COMPLETED);
        when(backupRepository.findByBackupIdAndProjectNameAndDatabaseId(
                "bkp-source0001", "orders", "db-source0001")).thenReturn(Optional.of(backup));
        when(strategies.require(DatabaseEngine.POSTGRESQL)).thenReturn(strategy(true));
    }

    @Test
    void fullRestoreRequiresACompletedBackup() {
        backup.setStatus(BackupStatus.FAILED);

        ApiException exception = assertThrows(ApiException.class,
                () -> service.restore("orders", "db-source0001", "restore-key-001",
                        restoreRequest("orders-restore")));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        verify(databaseRepository, never()).save(any(DatabaseMetadata.class));
    }

    @Test
    void inPlaceRestoreDoesNotCreateTemporaryDatabaseMetadata() {
        var response = service.restore("orders", "db-source0001", "restore-key-002",
                restoreRequest("orders-restore"));

        assertEquals("db-source0001", response.databaseId());
        var captor = forClass(RestoreRequestMetadata.class);
        verify(restoreRepository).save(captor.capture());
        assertEquals("db-source0001", captor.getValue().getRestoredDatabaseId());
        assertNotEquals("db-source0001", captor.getValue().getTemporaryClusterName());
        assertEquals("db-source0001", captor.getValue().getOldClusterName());
    }

    @Test
    void repeatedIdempotencyKeyReturnsTheOriginalRestoreEvenWhenActive() {
        RestoreRequestMetadata existing = restore(RestoreStatus.RUNNING);
        existing.setRequestHash(hash(RestoreMode.FULL.name(), "bkp-source0001", "IN_PLACE",
                String.valueOf(true)));
        when(restoreRepository.findByProjectNameAndSourceDatabaseIdAndIdempotencyKey(
                "orders", "db-source0001", "restore-key-003")).thenReturn(Optional.of(existing));

        var response = service.restore("orders", "db-source0001", "restore-key-003",
                restoreRequest("orders-restore"));

        assertEquals(existing.getRestoreId(), response.restoreId());
        verify(databaseRepository, never()).save(any(DatabaseMetadata.class));
    }

    @Test
    void promoteRequiresReadyAndRemovesExpiry() {
        RestoreRequestMetadata existing = restore(RestoreStatus.READY);
        when(restoreRepository.findByRestoreIdAndProjectNameAndSourceDatabaseId(
                "rst-existing001", "orders", "db-source0001")).thenReturn(Optional.of(existing));

        var response = service.promote("orders", "db-source0001", "rst-existing001");

        assertEquals(false, response.temporary());
        assertNull(response.expiresAt());
        assertNull(existing.getExpiresAfterHours());
        assertNotEquals(null, existing.getPromotedAt());
        verify(databaseService, never()).delete(anyString(), anyString());
    }

    @Test
    void deleteTemporaryRestoreDeletesOnlyTheRestoredDatabase() {
        RestoreRequestMetadata existing = restore(RestoreStatus.READY);
        when(restoreRepository.findByRestoreIdAndProjectNameAndSourceDatabaseId(
                "rst-existing001", "orders", "db-source0001")).thenReturn(Optional.of(existing));

        service.deleteTemporary("orders", "db-source0001", "rst-existing001");

        verify(databaseService).delete("orders", "db-restored001");
        verify(databaseService, never()).delete("orders", "db-source0001");
    }

    @Test
    void expiredTemporaryRestoreDeletesOnlyTheRestoredDatabase() {
        RestoreRequestMetadata existing = restore(RestoreStatus.READY);

        service.expireTemporaryRestore(existing);

        assertEquals(RestoreStatus.EXPIRED, existing.getStatus());
        verify(databaseService).delete("orders", "db-restored001");
        verify(databaseService, never()).delete("orders", "db-source0001");
    }

    private CreateRestoreRequest restoreRequest(String targetName) {
        return new CreateRestoreRequest(RestoreMode.FULL, "bkp-source0001", null,
                targetName, true, 24, RestoreAccessMode.PRIVATE, "IN_PLACE", true, "orders");
    }

    private RestoreRequestMetadata restore(RestoreStatus status) {
        RestoreRequestMetadata restore = new RestoreRequestMetadata();
        restore.setRestoreId("rst-existing001");
        restore.setOperationId("op-existing001");
        restore.setProjectName("orders");
        restore.setSourceDatabaseId("db-source0001");
        restore.setSourceBackupId("bkp-source0001");
        restore.setRestoreMode(RestoreMode.FULL);
        restore.setRestoredDatabaseId("db-restored001");
        restore.setTargetDatabaseName("orders-restore");
        restore.setTemporary(true);
        restore.setExpiresAfterHours(24);
        restore.setExpiresAt(Instant.now().plusSeconds(86400));
        restore.setAccessMode(RestoreAccessMode.PRIVATE);
        restore.setStatus(status);
        restore.setIdempotencyKey("restore-key-003");
        restore.setRequestHash("hash");
        restore.setCreatedAt(Instant.now());
        return restore;
    }

    private DatabaseMetadata database(String id, String project) {
        DatabaseMetadata database = new DatabaseMetadata();
        database.setDatabaseId(id);
        database.setProjectName(project);
        database.setNamespaceName("dbaas-orders");
        database.setDisplayName("orders");
        database.setEngine(DatabaseEngine.POSTGRESQL);
        database.setMode(DatabaseMode.REPLICATION);
        database.setDatabaseVersion("17");
        database.setStatus(DatabaseStatus.RUNNING);
        database.setDesiredState(DesiredState.RUNNING);
        database.setProvisioningStage(ProvisioningStage.READY);
        database.setReplicas(2);
        database.setShards(1);
        database.setStorageGi(20);
        return database;
    }

    private BackupMetadata backup(BackupStatus status) {
        BackupMetadata metadata = new BackupMetadata();
        metadata.setBackupId("bkp-source0001");
        metadata.setProjectName("orders");
        metadata.setDatabaseId("db-source0001");
        metadata.setEngine(DatabaseEngine.POSTGRESQL);
        metadata.setBackupType(BackupType.FULL);
        metadata.setBackupMethod("pg-full");
        metadata.setTriggerMethod(BackupTriggerMethod.MANUAL);
        metadata.setKubernetesBackupName("kb-backup");
        metadata.setStatus(status);
        metadata.setRetentionPeriod("7d");
        metadata.setIdempotencyKey("backup-key");
        metadata.setRequestHash("hash");
        metadata.setCompletedAt(Instant.now());
        return metadata;
    }

    private BackupEngineStrategy strategy(boolean pitrSupported) {
        return new BackupEngineStrategy() {
            @Override public DatabaseEngine engine() { return DatabaseEngine.POSTGRESQL; }
            @Override public String manualFullMethod() { return "pg-full"; }
            @Override public String continuousMethod() { return "pg-continuous"; }
            @Override public List<String> futureIncrementalMethods() { return List.of(); }
            @Override public List<String> futureContinuousMethods() { return List.of("pg-continuous"); }
            @Override public boolean supportsTopology(DatabaseMode mode) { return true; }
            @Override public boolean supportsPitrTopology(DatabaseMode mode) { return pitrSupported; }
        };
    }

    private String hash(String... values) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(String.join("|", values).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
