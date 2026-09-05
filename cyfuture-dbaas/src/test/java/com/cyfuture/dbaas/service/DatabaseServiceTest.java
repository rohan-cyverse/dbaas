package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.client.DatabaseObservation;
import com.cyfuture.dbaas.config.DatabaseProperties;
import com.cyfuture.dbaas.dto.CreateDatabaseRequest;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseServiceTest {
    private DatabaseMetadataRepository repository;
    private AsyncProvisioningService provisioning;
    private MetadataCreationService metadataCreation;
    private ProjectService projects;
    private FriendlyNameGenerator friendlyNames;
    private KubeBlocksClient kubeBlocksClient;
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
        service = new DatabaseService(kubeBlocksClient, properties, repository,
                provisioning, metadataCreation, mock(CredentialLifecycleService.class),
                projects, mock(SharedGatewayService.class), mock(OperationMetadataRepository.class), friendlyNames);
    }

    @Test
    void createStoresProjectScopedMetadataAndCallerCidr() {
        service.create("orders", "create-orders-001", request(), "157.37.137.185");

        ArgumentCaptor<DatabaseMetadata> database = ArgumentCaptor.forClass(DatabaseMetadata.class);
        ArgumentCaptor<OperationMetadata> operation = ArgumentCaptor.forClass(OperationMetadata.class);
        verify(metadataCreation).save(database.capture(), operation.capture());
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
                "Asia/Kolkata", null, true, Map.of("env", "test"));

        var response = service.create("orders", "create-orders-003", unnamed, "157.37.137.185");

        ArgumentCaptor<DatabaseMetadata> database = ArgumentCaptor.forClass(DatabaseMetadata.class);
        verify(metadataCreation).save(database.capture(), any());
        assertEquals("pg-quiet-mango-a7k9", database.getValue().getDisplayName());
        assertEquals("pg-quiet-mango-a7k9", response.name());
    }

    @Test
    void addsAShortSuffixWhenTheRequestedDatabaseNameIsAlreadyTaken() {
        when(repository.existsByProjectNameAndDisplayName("orders", "orders-db")).thenReturn(true);
        when(friendlyNames.nextShortSuffix()).thenReturn("m4p7");

        var response = service.create("orders", "create-orders-004", request(), "157.37.137.185");

        ArgumentCaptor<DatabaseMetadata> database = ArgumentCaptor.forClass(DatabaseMetadata.class);
        verify(metadataCreation).save(database.capture(), any());
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
                        1, 1, 1, true, "db-orders0001.dbaas-orders.svc", 5432, "ready"));

        var response = service.setDeletionProtection("orders", "db-orders0001", true);

        assertTrue(database.isDeletionProtection());
        assertTrue(response.deletionProtection());
        verify(kubeBlocksClient).setDeletionProtection("dbaas-orders", "db-orders0001", true);
        verify(repository).save(database);
    }

    private CreateDatabaseRequest request() {
        return new CreateDatabaseRequest("orders-db", "Orders", DatabaseEngine.POSTGRESQL,
                DatabaseMode.STANDALONE, "17.5.0", SizePlan.C1G2, 10, 1, 0,
                "Asia/Kolkata", null, true, Map.of("env", "test"));
    }
}
