package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.dto.AccessRulesRequest;
import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.client.DatabaseObservation;
import com.cyfuture.dbaas.config.DatabaseProperties;
import com.cyfuture.dbaas.dto.BackupSettingsRequest;
import com.cyfuture.dbaas.dto.CreateDatabaseRequest;
import com.cyfuture.dbaas.entity.BackupPolicyMetadata;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.entity.OperationMetadata;
import com.cyfuture.dbaas.entity.ProjectMetadata;
import com.cyfuture.dbaas.exception.ApiException;
import com.cyfuture.dbaas.model.DatabaseEngine;
import com.cyfuture.dbaas.model.DatabaseMode;
import com.cyfuture.dbaas.model.DatabaseStatus;
import com.cyfuture.dbaas.model.ProvisioningStage;
import com.cyfuture.dbaas.model.SizePlan;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import com.cyfuture.dbaas.repository.OperationMetadataRepository;
import com.cyfuture.dbaas.repository.BackupMetadataRepository;
import com.cyfuture.dbaas.repository.RestoreRequestMetadataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseServiceTest {
    private DatabaseMetadataRepository repository;
    private AsyncProvisioningService provisioning;
    private MetadataCreationService metadataCreation;
    private ProjectService projects;
    private FriendlyNameGenerator friendlyNames;
    private KubeBlocksClient kubeBlocksClient;
    private BackupPolicyService backupPolicyService;
    private SharedGatewayService sharedGatewayService;
    private DatabaseService service;

    @BeforeEach
    void setUp() {
        repository = mock(DatabaseMetadataRepository.class);
        provisioning = mock(AsyncProvisioningService.class);
        metadataCreation = mock(MetadataCreationService.class);
        projects = mock(ProjectService.class);
        friendlyNames = mock(FriendlyNameGenerator.class);
        DatabaseProperties properties = new DatabaseProperties();
        properties.getPostgresql().setVersions(List.of("17.5.0"));
        ProjectMetadata project = new ProjectMetadata();
        project.setProjectId("prj-orders0001");
        project.setNamespaceName("dbaas-orders");
        when(projects.requireActiveProject("orders")).thenReturn(project);
        when(repository.findByProjectNameAndIdempotencyKey(anyString(), anyString()))
                .thenReturn(Optional.empty());
        kubeBlocksClient = mock(KubeBlocksClient.class);
        backupPolicyService = mock(BackupPolicyService.class);
        sharedGatewayService = mock(SharedGatewayService.class);
        when(backupPolicyService.normalizeForCreation(any(BackupSettingsRequest.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(backupPolicyService.initialPolicy(any(DatabaseMetadata.class),
                any(BackupSettingsRequest.class), anyString()))
                .thenReturn(new BackupPolicyMetadata());
        service = new DatabaseService(kubeBlocksClient, properties, repository,
                provisioning, metadataCreation, mock(CredentialLifecycleService.class),
                projects, sharedGatewayService, mock(OperationMetadataRepository.class), friendlyNames,
                mock(BackupMetadataRepository.class), mock(RestoreRequestMetadataRepository.class),
                backupPolicyService, mock(BackupRetentionService.class));
    }

    @Test
    void createStoresProjectScopedMetadataAndCallerCidr() {
        service.create("orders", "create-orders-001", request(), "157.37.137.185");

        ArgumentCaptor<DatabaseMetadata> database = ArgumentCaptor.forClass(DatabaseMetadata.class);
        ArgumentCaptor<OperationMetadata> operation = ArgumentCaptor.forClass(OperationMetadata.class);
        verify(metadataCreation).save(database.capture(), operation.capture(), any(BackupPolicyMetadata.class));
        assertEquals("orders", database.getValue().getProjectName());
        assertEquals("dbaas-orders", database.getValue().getNamespaceName());
        assertEquals("orders", operation.getValue().getProjectName());
        verify(provisioning).provision(anyString(), anyString(),
                anyString(), anyString(), any(CreateDatabaseRequest.class));
    }

    @Test
    void createRequiresDetectableCallerIp() {
        assertThrows(ApiException.class,
                () -> service.create("orders", "create-orders-002", request(), null));
    }

    @Test
    void createsFriendlyDatabaseNameWhenNameIsOmitted() {
        when(friendlyNames.nextDatabaseName(DatabaseEngine.POSTGRESQL))
                .thenReturn("pg-quiet-mango-a7k9");
        CreateDatabaseRequest unnamed = new CreateDatabaseRequest(null, "Orders", DatabaseEngine.POSTGRESQL,
                DatabaseMode.STANDALONE, "17.5.0", SizePlan.C1G2, 10, 1, 0,
                "Asia/Kolkata", null, true, Map.of("env", "test"), backup());

        var response = service.create("orders", "create-orders-003", unnamed, "157.37.137.185");

        ArgumentCaptor<DatabaseMetadata> database = ArgumentCaptor.forClass(DatabaseMetadata.class);
        verify(metadataCreation).save(database.capture(), any(OperationMetadata.class), any(BackupPolicyMetadata.class));
        assertEquals("pg-quiet-mango-a7k9", database.getValue().getDisplayName());
        assertEquals("pg-quiet-mango-a7k9", response.name());
    }

    @Test
    void addsAShortSuffixWhenTheRequestedDatabaseNameIsAlreadyTaken() {
        when(repository.existsByProjectNameAndDisplayName("orders", "orders-db")).thenReturn(true);
        when(friendlyNames.nextShortSuffix()).thenReturn("m4p7");

        var response = service.create("orders", "create-orders-004", request(), "157.37.137.185");

        ArgumentCaptor<DatabaseMetadata> database = ArgumentCaptor.forClass(DatabaseMetadata.class);
        verify(metadataCreation).save(database.capture(), any(OperationMetadata.class), any(BackupPolicyMetadata.class));
        assertEquals("orders-db-m4p7", database.getValue().getDisplayName());
        assertEquals("orders-db-m4p7", response.name());
    }

    @Test
    void connectionUsesATransactionForItsPessimisticMetadataLock() throws NoSuchMethodException {
        assertTrue(DatabaseService.class
                .getMethod("connection", String.class, String.class, String.class)
                .isAnnotationPresent(Transactional.class));
    }

    @Test
    void mongoConnectionUriAuthenticatesAgainstManagedDatabase() throws Exception {
        var connectionUri = DatabaseService.class.getDeclaredMethod("connectionUri",
                DatabaseEngine.class, DatabaseMode.class, boolean.class,
                String.class, String.class, String.class, int.class, String.class);
        connectionUri.setAccessible(true);

        String uri = (String) connectionUri.invoke(service,
                DatabaseEngine.MONGODB, DatabaseMode.STANDALONE, true,
                "user", "pass", "mongo.example.com", 27017, "appdb_xxx");

        assertEquals("mongodb://user:pass@mongo.example.com:27017/appdb_xxx"
                        + "?authSource=appdb_xxx&directConnection=true",
                uri);
    }

    @Test
    void updatesDeletionProtectionForAnActiveDatabase() {
        DatabaseMetadata database = new DatabaseMetadata();
        database.setDatabaseId("db-orders0001");
        database.setProjectName("orders");
        database.setNamespaceName("dbaas-orders");
        database.setStatus(DatabaseStatus.RUNNING);
        database.setProvisioningStage(ProvisioningStage.READY);
        database.setDeletionProtection(false);
        when(repository.findByDatabaseIdAndProjectName("db-orders0001", "orders"))
                .thenReturn(Optional.of(database));
        when(kubeBlocksClient.setDeletionProtection("dbaas-orders", "db-orders0001", true))
                .thenReturn(new DatabaseObservation("db-orders0001", "orders-db",
                        DatabaseEngine.POSTGRESQL, DatabaseMode.STANDALONE, "17.5.0",
                        SizePlan.C1G2, 10, true, DatabaseStatus.RUNNING,
                        1, 1, 0, 0, 0, 0, 1, 1, true,
                        "db-orders0001.dbaas-orders.svc", 5432, List.of(), "ready"));

        var response = service.setDeletionProtection("orders", "db-orders0001", true);

        assertTrue(database.isDeletionProtection());
        assertTrue(response.deletionProtection());
        verify(kubeBlocksClient).setDeletionProtection("dbaas-orders", "db-orders0001", true);
        verify(repository).save(database);
    }

    @Test
    void updatesAccessRulesAndReconcilesGateway() {
        DatabaseMetadata database = database("db-orders0001");
        database.setAllowedCidrs("[49.50.73.146/32]");
        when(repository.findByDatabaseIdAndProjectName("db-orders0001", "orders"))
                .thenReturn(Optional.of(database));

        var response = service.updateAccessRules("orders", "db-orders0001",
                new AccessRulesRequest(List.of("203.0.113.0/24"), true),
                "157.37.137.185");

        assertEquals(List.of("157.37.137.185/32", "203.0.113.0/24"), response.allowedCidrs());
        assertEquals("[157.37.137.185/32, 203.0.113.0/24]", database.getAllowedCidrs());
        verify(repository).save(database);
        verify(sharedGatewayService).reconcileNow();
    }

    @Test
    void rejectsOpenInternetAccessRule() {
        DatabaseMetadata database = database("db-orders0001");
        when(repository.findByDatabaseIdAndProjectName("db-orders0001", "orders"))
                .thenReturn(Optional.of(database));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.updateAccessRules("orders", "db-orders0001",
                        new AccessRulesRequest(List.of("0.0.0.0/0"), false), null));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        verify(repository, never()).save(database);
    }

    @Test
    void listsOneLogicalDatabaseWithObservedHaInstanceCounts() {
        DatabaseMetadata database = new DatabaseMetadata();
        database.setDatabaseId("db-orders0001");
        database.setDisplayName("orders-db");
        database.setProjectName("orders");
        database.setNamespaceName("dbaas-orders");
        database.setEngine(DatabaseEngine.POSTGRESQL);
        database.setMode(DatabaseMode.REPLICATION);
        database.setDatabaseVersion("17.5.0");
        database.setSizePlan(SizePlan.C1G2);
        database.setStorageGi(20);
        database.setReplicas(3);
        database.setShards(0);
        database.setStatus(DatabaseStatus.RUNNING);
        database.setProvisioningStage(ProvisioningStage.READY);
        database.setProgress(100);
        when(repository.findByProjectNameOrderByCreatedAtDesc("orders")).thenReturn(List.of(database));
        when(repository.findByDatabaseIdAndProjectName("db-orders0001", "orders"))
                .thenReturn(Optional.of(database));
        when(kubeBlocksClient.get("dbaas-orders", "db-orders0001"))
                .thenReturn(new DatabaseObservation("db-orders0001", "orders-db",
                        DatabaseEngine.POSTGRESQL, DatabaseMode.REPLICATION, "17.5.0",
                        SizePlan.C1G2, 20, true, DatabaseStatus.RUNNING,
                        3, 1, 2, 0, 0, 0, 3, 3, true,
                        "db-orders0001-postgresql.dbaas-orders.svc", 5432,
                        List.of(
                                new DatabaseObservation.TopologyMember("db-orders0001-postgresql-0",
                                        "primary", "postgresql", true),
                                new DatabaseObservation.TopologyMember("db-orders0001-postgresql-1",
                                        "replica", "postgresql", true),
                                new DatabaseObservation.TopologyMember("db-orders0001-postgresql-2",
                                        "replica", "postgresql", true)),
                        "ready"));

        List<com.cyfuture.dbaas.dto.DatabaseResponse> responses = service.list("orders");

        assertEquals(1, responses.size());
        com.cyfuture.dbaas.dto.DatabaseResponse response = responses.get(0);
        assertEquals("db-orders0001", response.databaseId());
        assertEquals(DatabaseMode.REPLICATION, response.deploymentMode());
        assertEquals(SizePlan.C1G2, response.sizePlan());
        assertEquals(3, response.instanceCount());
        assertEquals(1, response.primaryCount());
        assertEquals(2, response.replicaCount());
        assertEquals(0, response.topology().members().size());

        com.cyfuture.dbaas.dto.DatabaseResponse details = service.get("orders", "db-orders0001");

        assertEquals(3, details.instanceCount());
        assertEquals(3, details.topology().members().size());
    }

    @Test
    void explainsWhenDatabaseDeletionIsBlockedByDeletionProtection() {
        DatabaseMetadata database = new DatabaseMetadata();
        database.setDatabaseId("db-orders0001");
        database.setProjectName("orders");
        database.setStatus(DatabaseStatus.RUNNING);
        database.setProvisioningStage(ProvisioningStage.READY);
        database.setDeletionProtection(true);
        when(repository.findByDatabaseIdAndProjectName("db-orders0001", "orders"))
                .thenReturn(Optional.of(database));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.delete("orders", "db-orders0001"));

        assertEquals(org.springframework.http.HttpStatus.CONFLICT, exception.getStatus());
        assertEquals("DELETION_PROTECTION_ENABLED", exception.getCode());
        assertEquals("Deletion protection is enabled for db-orders0001. Disable it before deleting.",
                exception.getMessage());
    }

    @Test
    void blocksDeletionWhenKubernetesReportsAnUnimportedActiveBackup() {
        DatabaseMetadata database = new DatabaseMetadata();
        database.setDatabaseId("db-orders0001");
        database.setProjectName("orders");
        database.setNamespaceName("dbaas-orders");
        database.setStatus(DatabaseStatus.RUNNING);
        database.setProvisioningStage(ProvisioningStage.READY);
        database.setDeletionProtection(false);
        when(repository.findByDatabaseIdAndProjectName("db-orders0001", "orders"))
                .thenReturn(Optional.of(database));
        when(kubeBlocksClient.hasActiveBackup("dbaas-orders", "db-orders0001")).thenReturn(true);

        ApiException exception = assertThrows(ApiException.class,
                () -> service.delete("orders", "db-orders0001"));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals("BACKUP_OR_RESTORE_IN_PROGRESS", exception.getCode());
        verify(kubeBlocksClient, never()).requestDelete("dbaas-orders", "db-orders0001");
    }

    @Test
    void createRequiresBackupConfiguration() {
        CreateDatabaseRequest missingBackup = new CreateDatabaseRequest("orders-db", "Orders",
                DatabaseEngine.POSTGRESQL, DatabaseMode.STANDALONE, "17.5.0", SizePlan.C1G2,
                10, 1, 0, "Asia/Kolkata", null, true, Map.of("env", "test"));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.create("orders", "create-orders-005", missingBackup, "157.37.137.185"));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        assertEquals("BACKUP_SETTINGS_REQUIRED", exception.getCode());
    }

    @Test
    void createRequiresCompleteBackupConfiguration() {
        CreateDatabaseRequest incompleteBackup = new CreateDatabaseRequest("orders-db", "Orders",
                DatabaseEngine.POSTGRESQL, DatabaseMode.STANDALONE, "17.5.0", SizePlan.C1G2,
                10, 1, 0, "Asia/Kolkata", null, true, Map.of("env", "test"),
                new BackupSettingsRequest(true, null, "0 2 * * *", "UTC", false));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.create("orders", "create-orders-006", incompleteBackup, "157.37.137.185"));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        assertEquals("BACKUP_SETTINGS_INCOMPLETE", exception.getCode());
    }

    private CreateDatabaseRequest request() {
        return new CreateDatabaseRequest("orders-db", "Orders", DatabaseEngine.POSTGRESQL,
                DatabaseMode.STANDALONE, "17.5.0", SizePlan.C1G2, 10, 1, 0,
                "Asia/Kolkata", null, true, Map.of("env", "test"), backup());
    }

    private BackupSettingsRequest backup() {
        return new BackupSettingsRequest(true, 7, "0 2 * * *", "UTC", false);
    }

    private DatabaseMetadata database(String databaseId) {
        DatabaseMetadata database = new DatabaseMetadata();
        database.setDatabaseId(databaseId);
        database.setProjectName("orders");
        database.setNamespaceName("dbaas-orders");
        database.setDisplayName("orders-db");
        database.setEngine(DatabaseEngine.POSTGRESQL);
        database.setMode(DatabaseMode.STANDALONE);
        database.setDatabaseVersion("17.5.0");
        database.setSizePlan(SizePlan.C1G2);
        database.setStorageGi(10);
        database.setStatus(DatabaseStatus.RUNNING);
        database.setProvisioningStage(ProvisioningStage.READY);
        database.setProgress(100);
        return database;
    }
}
