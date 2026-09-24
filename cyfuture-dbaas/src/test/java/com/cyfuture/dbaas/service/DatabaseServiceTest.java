package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.dto.AccessRulesRequest;
import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.client.DatabaseObservation;
import com.cyfuture.dbaas.config.DatabaseProperties;
import com.cyfuture.dbaas.dto.BackupSettingsRequest;
import com.cyfuture.dbaas.dto.CreateDatabaseRequest;
import com.cyfuture.dbaas.dto.PublicEndpointResponse;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    private BackupRetentionService backupRetentionService;
    private CredentialLifecycleService credentialLifecycleService;
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
        backupRetentionService = mock(BackupRetentionService.class);
        when(backupRetentionService.readyForClusterDeletion(anyString(), anyString())).thenReturn(true);
        credentialLifecycleService = mock(CredentialLifecycleService.class);
        sharedGatewayService = mock(SharedGatewayService.class);
        when(backupPolicyService.normalizeForCreation(any(BackupSettingsRequest.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(backupPolicyService.initialPolicy(any(DatabaseMetadata.class),
                any(BackupSettingsRequest.class), anyString()))
                .thenReturn(new BackupPolicyMetadata());
        service = new DatabaseService(kubeBlocksClient, properties, repository,
                provisioning, metadataCreation, credentialLifecycleService,
                projects, sharedGatewayService, mock(OperationMetadataRepository.class), friendlyNames,
                mock(BackupMetadataRepository.class), mock(RestoreRequestMetadataRepository.class),
                backupPolicyService, backupRetentionService);
    }

    @Test
    void createStoresProjectScopedMetadataAndCallerCidr() {
        service.create("orders", "create-orders-001", request(), "157.37.137.185");

        ArgumentCaptor<DatabaseMetadata> database = ArgumentCaptor.forClass(DatabaseMetadata.class);
        ArgumentCaptor<OperationMetadata> operation = ArgumentCaptor.forClass(OperationMetadata.class);
        verify(metadataCreation).save(database.capture(), operation.capture(), any(BackupPolicyMetadata.class));
        assertEquals("orders", database.getValue().getProjectName());
        assertEquals("dbaas-orders", database.getValue().getNamespaceName());
        assertEquals("orders_db", database.getValue().getLogicalDatabaseName());
        assertEquals("orders_user", database.getValue().getLogicalUsername());
        assertEquals("orders", operation.getValue().getProjectName());
        verify(provisioning).provision(anyString(), anyString(),
                anyString(), anyString(), any(CreateDatabaseRequest.class));
    }

    @Test
    void createMergesRequestedAllowedCidrsWithCallerCidr() {
        CreateDatabaseRequest request = new CreateDatabaseRequest("orders_db", "orders_user", "Orders",
                DatabaseEngine.POSTGRESQL, DatabaseMode.STANDALONE, "17.5.0", SizePlan.C1G2,
                10, 1, 0, "Asia/Kolkata",
                List.of("0.0.0.0/0", "157.37.137.185/32"), true, Map.of("env", "test"),
                "S3cure_Pass-2026", backup());

        service.create("orders", "create-orders-cidrs", request, "157.37.137.185");

        ArgumentCaptor<DatabaseMetadata> database = ArgumentCaptor.forClass(DatabaseMetadata.class);
        verify(metadataCreation).save(database.capture(), any(OperationMetadata.class), any(BackupPolicyMetadata.class));
        assertEquals("[0.0.0.0/0, 157.37.137.185/32]", database.getValue().getAllowedCidrs());
    }

    @Test
    void createPassesRequestedPasswordToProvisioning() {
        CreateDatabaseRequest request = new CreateDatabaseRequest("orders_db", "orders_user", "Orders",
                DatabaseEngine.POSTGRESQL, DatabaseMode.STANDALONE, "17.5.0", SizePlan.C1G2,
                10, 1, 0, "Asia/Kolkata",
                null, true, Map.of("env", "test"), "S3cure_Pass-2026", backup());

        service.create("orders", "create-orders-password", request, "157.37.137.185");

        ArgumentCaptor<CreateDatabaseRequest> provisioningRequest =
                ArgumentCaptor.forClass(CreateDatabaseRequest.class);
        verify(provisioning).provision(anyString(), anyString(), anyString(), anyString(),
                provisioningRequest.capture());
        assertEquals("S3cure_Pass-2026", provisioningRequest.getValue().password());
    }

    @Test
    void createRequiresDetectableCallerIp() {
        assertThrows(ApiException.class,
                () -> service.create("orders", "create-orders-002", request(), null));
    }

    @Test
    void requiresUserDefinedDatabaseName() {
        CreateDatabaseRequest unnamed = new CreateDatabaseRequest(null, "orders_user", "Orders", DatabaseEngine.POSTGRESQL,
                DatabaseMode.STANDALONE, "17.5.0", SizePlan.C1G2, 10, 1, 0,
                "Asia/Kolkata", null, true, Map.of("env", "test"),
                "S3cure_Pass-2026", backup());

        ApiException exception = assertThrows(ApiException.class,
                () -> service.create("orders", "create-orders-003", unnamed, "157.37.137.185"));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        assertEquals("DATABASE_NAME_REQUIRED", exception.getCode());
        verify(metadataCreation, never()).save(any(), any(), any());
    }

    @Test
    void rejectsDuplicateRequestedDatabaseNameInsteadOfChangingIt() {
        when(repository.existsByProjectNameAndDisplayName("orders", "orders_db")).thenReturn(true);

        ApiException exception = assertThrows(ApiException.class,
                () -> service.create("orders", "create-orders-004", request(), "157.37.137.185"));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals("DATABASE_NAME_ALREADY_EXISTS", exception.getCode());
        verify(metadataCreation, never()).save(any(), any(), any());
    }

    @Test
    void rejectsDuplicateRequestedDatabaseUsername() {
        when(repository.existsByProjectNameAndLogicalUsername("orders", "orders_user"))
                .thenReturn(true);

        ApiException exception = assertThrows(ApiException.class,
                () -> service.create("orders", "create-orders-username", request(), "157.37.137.185"));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals("DATABASE_USERNAME_ALREADY_EXISTS", exception.getCode());
        assertTrue(exception.getMessage().contains("select a different username"));
        verify(metadataCreation, never()).save(any(), any(), any());
    }

    @Test
    void connectionUsesATransactionForItsPessimisticMetadataLock() throws NoSuchMethodException {
        assertTrue(DatabaseService.class
                .getMethod("connection", String.class, String.class, String.class)
                .isAnnotationPresent(Transactional.class));
    }

    @Test
    void connectionDetailsDoNotMutateAccessRulesOrReconcileGateway() {
        DatabaseMetadata database = database("db-orders0001");
        database.setStatus(DatabaseStatus.RUNNING);
        database.setProvisioningStage(ProvisioningStage.READY);
        database.setPublicPort(31000);
        database.setAllowedCidrs("[49.50.73.146/32]");
        when(repository.findByDatabaseIdAndProjectNameForUpdate("db-orders0001", "orders"))
                .thenReturn(Optional.of(database));
        when(kubeBlocksClient.get("dbaas-orders", "db-orders0001"))
                .thenReturn(observation(DatabaseStatus.RUNNING, true));
        when(credentialLifecycleService.credentials(database))
                .thenReturn(new ManagedCredential("app_user", "secret", "app_db"));
        when(sharedGatewayService.endpoint(database))
                .thenReturn(new PublicEndpointResponse("49.50.73.146", 31000,
                        true, List.of("49.50.73.146/32")));

        service.connection("orders", "db-orders0001", "203.0.113.55");

        assertEquals("[49.50.73.146/32]", database.getAllowedCidrs());
        verify(repository, never()).save(database);
        verify(sharedGatewayService, never()).reconcileNow();
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
    void shardedMongoConnectionUriDoesNotForceDirectConnection() throws Exception {
        var connectionUri = DatabaseService.class.getDeclaredMethod("connectionUri",
                DatabaseEngine.class, DatabaseMode.class, boolean.class,
                String.class, String.class, String.class, int.class, String.class);
        connectionUri.setAccessible(true);

        String uri = (String) connectionUri.invoke(service,
                DatabaseEngine.MONGODB, DatabaseMode.SHARDING, true,
                "user", "pass", "mongo.example.com", 27017, "appdb_xxx");

        assertEquals("mongodb://user:pass@mongo.example.com:27017/appdb_xxx"
                + "?authSource=appdb_xxx", uri);
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
    void addsAccessRulesByAppendingAndReconcilesGateway() {
        DatabaseMetadata database = database("db-orders0001");
        database.setPublicPort(31000);
        database.setAllowedCidrs("[49.50.73.146/32]");
        when(repository.findByDatabaseIdAndProjectName("db-orders0001", "orders"))
                .thenReturn(Optional.of(database));

        var response = service.updateAccessRules("orders", "db-orders0001",
                new AccessRulesRequest(List.of("203.0.113.0/24", "203.0.113.0/24", "157.37.137.185"), true),
                "157.37.137.185");

        assertEquals(List.of("157.37.137.185/32", "203.0.113.0/24", "49.50.73.146/32"),
                response.allowedCidrs());
        assertEquals("[157.37.137.185/32, 203.0.113.0/24, 49.50.73.146/32]",
                database.getAllowedCidrs());
        verify(repository).save(database);
        verify(sharedGatewayService).reconcileNow();
    }

    @Test
    void addAccessRuleTrimsCidrAndDoesNotReplaceExistingRules() {
        DatabaseMetadata database = database("db-orders0001");
        database.setPublicPort(31000);
        database.setAllowedCidrs("[49.50.73.146/32]");
        when(repository.findByDatabaseIdAndProjectName("db-orders0001", "orders"))
                .thenReturn(Optional.of(database));

        var response = service.updateAccessRules("orders", "db-orders0001",
                new AccessRulesRequest(List.of(), false, List.of("157.49.126.77/32 "), List.of()),
                null);

        assertEquals(List.of("157.49.126.77/32", "49.50.73.146/32"), response.allowedCidrs());
        assertEquals("[157.49.126.77/32, 49.50.73.146/32]", database.getAllowedCidrs());
        verify(repository).save(database);
        verify(sharedGatewayService).reconcileNow();
    }

    @Test
    void addAccessRuleAcceptsSingleCidrApiPayloadAlias() {
        DatabaseMetadata database = database("db-orders0001");
        database.setPublicPort(31000);
        database.setAllowedCidrs("[49.50.73.146/32]");
        when(repository.findByDatabaseIdAndProjectName("db-orders0001", "orders"))
                .thenReturn(Optional.of(database));

        var response = service.updateAccessRules("orders", "db-orders0001",
                new AccessRulesRequest(List.of(), false, List.of(), List.of(),
                        "157.49.126.77/32", null, null),
                null);

        assertEquals(List.of("157.49.126.77/32", "49.50.73.146/32"), response.allowedCidrs());
        assertEquals("[157.49.126.77/32, 49.50.73.146/32]", database.getAllowedCidrs());
        verify(repository).save(database);
        verify(sharedGatewayService).reconcileNow();
    }

    @Test
    void removesOnlySelectedAccessRuleAndKeepsTheRest() {
        DatabaseMetadata database = database("db-orders0001");
        database.setPublicPort(31000);
        database.setAllowedCidrs("[49.50.73.146/32, 203.0.113.0/24, 157.37.137.185/32]");
        when(repository.findByDatabaseIdAndProjectName("db-orders0001", "orders"))
                .thenReturn(Optional.of(database));

        var response = service.updateAccessRules("orders", "db-orders0001",
                new AccessRulesRequest(List.of(), false, List.of(), List.of("203.0.113.0/24")),
                null);

        assertEquals(List.of("157.37.137.185/32", "49.50.73.146/32"), response.allowedCidrs());
        assertEquals("[157.37.137.185/32, 49.50.73.146/32]", database.getAllowedCidrs());
        verify(repository).save(database);
        verify(sharedGatewayService).reconcileNow();
    }

    @Test
    void removeAccessRuleAcceptsSingleCidrApiPayloadAlias() {
        DatabaseMetadata database = database("db-orders0001");
        database.setPublicPort(31000);
        database.setAllowedCidrs("[49.50.73.146/32, 157.49.126.77/32]");
        when(repository.findByDatabaseIdAndProjectName("db-orders0001", "orders"))
                .thenReturn(Optional.of(database));

        var response = service.updateAccessRules("orders", "db-orders0001",
                new AccessRulesRequest(List.of(), false, List.of(), List.of(),
                        null, null, "157.49.126.77/32"),
                null);

        assertEquals(List.of("49.50.73.146/32"), response.allowedCidrs());
        assertEquals("[49.50.73.146/32]", database.getAllowedCidrs());
        verify(repository).save(database);
        verify(sharedGatewayService).reconcileNow();
    }

    @Test
    void rejectsRemovingFinalRuleFromPublicDatabase() {
        DatabaseMetadata database = database("db-orders0001");
        database.setPublicPort(31000);
        database.setAllowedCidrs("[49.50.73.146/32]");
        when(repository.findByDatabaseIdAndProjectName("db-orders0001", "orders"))
                .thenReturn(Optional.of(database));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.updateAccessRules("orders", "db-orders0001",
                        new AccessRulesRequest(List.of(), false, List.of(), List.of("49.50.73.146/32")),
                        null));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        assertEquals("ACCESS_RULE_REQUIRED", exception.getCode());
        verify(repository, never()).save(database);
        verify(sharedGatewayService, never()).reconcileNow();
    }

    @Test
    void privateDatabaseMayHaveNoPublicAccessRules() {
        DatabaseMetadata database = database("db-orders0001");
        database.setPublicPort(null);
        database.setAllowedCidrs("[49.50.73.146/32]");
        when(repository.findByDatabaseIdAndProjectName("db-orders0001", "orders"))
                .thenReturn(Optional.of(database));

        var response = service.updateAccessRules("orders", "db-orders0001",
                new AccessRulesRequest(List.of(), false, List.of(), List.of("49.50.73.146/32")),
                null);

        assertTrue(response.allowedCidrs().isEmpty());
        assertEquals("[]", database.getAllowedCidrs());
        verify(repository).save(database);
        verify(sharedGatewayService).reconcileNow();
    }

    @Test
    void allowsOpenInternetAccessRuleWhenExplicitlyRequested() {
        DatabaseMetadata database = database("db-orders0001");
        database.setPublicPort(31000);
        when(repository.findByDatabaseIdAndProjectName("db-orders0001", "orders"))
                .thenReturn(Optional.of(database));

        var response = service.updateAccessRules("orders", "db-orders0001",
                new AccessRulesRequest(List.of("0.0.0.0/0"), false), null);

        assertEquals(List.of("0.0.0.0/0"), response.allowedCidrs());
        assertEquals("[0.0.0.0/0]", database.getAllowedCidrs());
        verify(repository).save(database);
        verify(sharedGatewayService).reconcileNow();
    }

    @Test
    void rejectsNullAccessRuleWithAClientError() {
        DatabaseMetadata database = database("db-orders0001");
        when(repository.findByDatabaseIdAndProjectName("db-orders0001", "orders"))
                .thenReturn(Optional.of(database));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.updateAccessRules("orders", "db-orders0001",
                        new AccessRulesRequest(java.util.Arrays.asList("203.0.113.0/24", null), false), null));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        assertEquals("INVALID_ACCESS_RULE", exception.getCode());
        verify(repository, never()).save(database);
        verify(sharedGatewayService, never()).reconcileNow();
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
    void deleteIgnoresDeletionProtectionAndRequestsClusterDeletion() {
        DatabaseMetadata database = new DatabaseMetadata();
        database.setDatabaseId("db-orders0001");
        database.setProjectName("orders");
        database.setNamespaceName("dbaas-orders");
        database.setStatus(DatabaseStatus.RUNNING);
        database.setProvisioningStage(ProvisioningStage.READY);
        database.setDeletionProtection(true);
        when(repository.findByDatabaseIdAndProjectName("db-orders0001", "orders"))
                .thenReturn(Optional.of(database));
        when(credentialLifecycleService.cleanupDatabaseResources(database)).thenReturn(
                new CredentialLifecycleService.CredentialCleanupObservation(true, 0, 0, 0, "gone"));
        when(kubeBlocksClient.observeCluster("dbaas-orders", "db-orders0001"))
                .thenReturn(new KubeBlocksClient.ClusterObservation(true, "dbaas-orders",
                        "db-orders0001", "Deleting", 1, 1, false, "deleting"));

        var response = service.delete("orders", "db-orders0001");

        assertEquals(DatabaseStatus.DELETING, response.status());
        assertEquals(DatabaseStatus.DELETING, database.getStatus());
        assertFalse(database.isDeletionProtection());
        verify(kubeBlocksClient).requestDelete("dbaas-orders", "db-orders0001");
    }

    @Test
    void deleteDoesNotWaitWhenKubernetesReportsAnUnimportedActiveBackup() {
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
        when(credentialLifecycleService.cleanupDatabaseResources(database)).thenReturn(
                new CredentialLifecycleService.CredentialCleanupObservation(true, 0, 0, 0, "gone"));
        when(kubeBlocksClient.observeCluster("dbaas-orders", "db-orders0001"))
                .thenReturn(new KubeBlocksClient.ClusterObservation(true, "dbaas-orders",
                        "db-orders0001", "Deleting", 1, 1, false, "deleting"));

        var response = service.delete("orders", "db-orders0001");

        assertEquals(DatabaseStatus.DELETING, response.status());
        assertEquals(DatabaseStatus.DELETING, database.getStatus());
        verify(backupRetentionService).prepareDatabaseBackupDeletion("orders", "db-orders0001");
        verify(kubeBlocksClient).requestDelete("dbaas-orders", "db-orders0001");
    }

    @Test
    void createRequiresBackupConfiguration() {
        CreateDatabaseRequest missingBackup = new CreateDatabaseRequest("orders_db", "orders_user", "Orders",
                DatabaseEngine.POSTGRESQL, DatabaseMode.STANDALONE, "17.5.0", SizePlan.C1G2,
                10, 1, 0, "Asia/Kolkata", null, true, Map.of("env", "test"));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.create("orders", "create-orders-005", missingBackup, "157.37.137.185"));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        assertEquals("BACKUP_SETTINGS_REQUIRED", exception.getCode());
    }

    @Test
    void createRequiresUserSuppliedPassword() {
        CreateDatabaseRequest missingPassword = new CreateDatabaseRequest("orders_db", "orders_user", "Orders",
                DatabaseEngine.POSTGRESQL, DatabaseMode.STANDALONE, "17.5.0", SizePlan.C1G2,
                10, 1, 0, "Asia/Kolkata", null, true, Map.of("env", "test"), backup());

        ApiException exception = assertThrows(ApiException.class,
                () -> service.create("orders", "create-orders-no-password", missingPassword,
                        "157.37.137.185"));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        assertEquals("DATABASE_PASSWORD_REQUIRED", exception.getCode());
        verify(provisioning, never()).provision(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void createRequiresUserSuppliedUsername() {
        CreateDatabaseRequest missingUsername = new CreateDatabaseRequest("orders_db", "Orders",
                DatabaseEngine.POSTGRESQL, DatabaseMode.STANDALONE, "17.5.0", SizePlan.C1G2,
                10, 1, 0, "Asia/Kolkata", null, true, Map.of("env", "test"),
                "S3cure_Pass-2026", backup());

        ApiException exception = assertThrows(ApiException.class,
                () -> service.create("orders", "create-orders-no-username", missingUsername,
                        "157.37.137.185"));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        assertEquals("DATABASE_USERNAME_REQUIRED", exception.getCode());
        verify(provisioning, never()).provision(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void createRequiresCompleteBackupConfiguration() {
        CreateDatabaseRequest incompleteBackup = new CreateDatabaseRequest("orders_db", "orders_user", "Orders",
                DatabaseEngine.POSTGRESQL, DatabaseMode.STANDALONE, "17.5.0", SizePlan.C1G2,
                10, 1, 0, "Asia/Kolkata", null, true, Map.of("env", "test"),
                new BackupSettingsRequest(true, null, "0 2 * * *", "UTC", false));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.create("orders", "create-orders-006", incompleteBackup, "157.37.137.185"));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        assertEquals("BACKUP_SETTINGS_INCOMPLETE", exception.getCode());
    }

    private CreateDatabaseRequest request() {
        return new CreateDatabaseRequest("orders_db", "orders_user", "Orders", DatabaseEngine.POSTGRESQL,
                DatabaseMode.STANDALONE, "17.5.0", SizePlan.C1G2, 10, 1, 0,
                "Asia/Kolkata", null, true, Map.of("env", "test"),
                "S3cure_Pass-2026", backup());
    }

    private BackupSettingsRequest backup() {
        return new BackupSettingsRequest(true, 7, "0 2 * * *", "UTC", false);
    }

    private DatabaseObservation observation(DatabaseStatus status, boolean serviceReady) {
        return new DatabaseObservation("db-orders0001", "orders-db",
                DatabaseEngine.POSTGRESQL, DatabaseMode.STANDALONE, "17.5.0",
                SizePlan.C1G2, 10, false, status, 1, 1, 0, 0, 0, 0,
                1, serviceReady ? 1 : 0, serviceReady,
                "db-orders0001.dbaas-orders.svc", 5432, List.of(),
                serviceReady ? "ready" : "service not ready");
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
