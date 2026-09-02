package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.config.DatabaseProperties;
import com.cyfuture.dbaas.dto.CreateDatabaseRequest;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.entity.OperationMetadata;
import com.cyfuture.dbaas.entity.ProjectMetadata;
import com.cyfuture.dbaas.exception.ApiException;
import com.cyfuture.dbaas.mapper.OperationMapper;
import com.cyfuture.dbaas.model.DatabaseEngine;
import com.cyfuture.dbaas.model.DatabaseMode;
import com.cyfuture.dbaas.model.DatabaseStatus;
import com.cyfuture.dbaas.model.OperationStatus;
import com.cyfuture.dbaas.model.OperationType;
import com.cyfuture.dbaas.model.ProvisioningStage;
import com.cyfuture.dbaas.model.SizePlan;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import com.cyfuture.dbaas.repository.OperationMetadataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    private KubeBlocksClient kubeBlocksClient;
    private SharedGatewayService gateway;
    private OperationMetadataRepository operationRepository;
    private DatabaseService service;

    @BeforeEach
    void setUp() {
        repository = mock(DatabaseMetadataRepository.class);
        provisioning = mock(AsyncProvisioningService.class);
        metadataCreation = mock(MetadataCreationService.class);
        projects = mock(ProjectService.class);
        kubeBlocksClient = mock(KubeBlocksClient.class);
        gateway = mock(SharedGatewayService.class);
        operationRepository = mock(OperationMetadataRepository.class);
        DatabaseProperties properties = new DatabaseProperties();
        properties.getPostgresql().setVersions(List.of("17.5.0"));
        ProjectMetadata project = new ProjectMetadata();
        project.setProjectName("orders");
        project.setNamespaceName("dbaas-orders");
        when(projects.requireActiveProject("orders")).thenReturn(project);
        when(repository.findByProjectNameAndIdempotencyKey(anyString(), anyString()))
                .thenReturn(Optional.empty());
        service = new DatabaseService(kubeBlocksClient, properties, repository,
                provisioning, metadataCreation, mock(CredentialLifecycleService.class),
                projects, gateway, operationRepository,
                new OperationMapper());
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
    void deletePersistsIntentAndDoesNotCallKubernetesInline() {
        DatabaseMetadata database = database(DatabaseStatus.RUNNING);
        when(repository.findByDatabaseIdAndProjectNameForUpdate("db-orders0001", "orders"))
                .thenReturn(Optional.of(database));
        when(operationRepository.findByDatabaseIdAndProjectNameAndStatusIn(
                "db-orders0001", "orders", List.of(OperationStatus.PENDING, OperationStatus.RUNNING)))
                .thenReturn(List.of());
        when(operationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.delete("orders", "db-orders0001");

        assertEquals(OperationType.DELETE, response.type());
        assertEquals(OperationStatus.RUNNING, response.status());
        assertEquals(DatabaseStatus.DELETING, database.getStatus());
        assertEquals(DatabaseStatus.DELETED, database.getDesiredStatus());
        verify(repository).save(database);
        verify(kubeBlocksClient, never()).requestDelete(anyString(), anyString());
        verify(gateway, never()).removeRoute(any());
    }

    @Test
    void repeatedDeleteReturnsExistingOperation() {
        DatabaseMetadata database = database(DatabaseStatus.DELETING);
        OperationMetadata existing = deleteOperation("op-delete0001");
        when(repository.findByDatabaseIdAndProjectNameForUpdate("db-orders0001", "orders"))
                .thenReturn(Optional.of(database));
        when(operationRepository.findByDatabaseIdAndProjectNameAndStatusIn(
                "db-orders0001", "orders", List.of(OperationStatus.PENDING, OperationStatus.RUNNING)))
                .thenReturn(List.of(existing));

        var response = service.delete("orders", "db-orders0001");

        assertEquals("op-delete0001", response.operationId());
        verify(kubeBlocksClient, never()).requestDelete(anyString(), anyString());
    }

    private CreateDatabaseRequest request() {
        return new CreateDatabaseRequest("orders-db", "Orders", DatabaseEngine.POSTGRESQL,
                DatabaseMode.STANDALONE, "17.5.0", SizePlan.C1G2, 10, 1, 0,
                "Asia/Kolkata", null, true, Map.of("env", "test"));
    }

    private DatabaseMetadata database(DatabaseStatus status) {
        DatabaseMetadata database = new DatabaseMetadata();
        database.setDatabaseId("db-orders0001");
        database.setProjectName("orders");
        database.setNamespaceName("dbaas-orders");
        database.setStatus(status);
        database.setDesiredStatus(status == DatabaseStatus.DELETING
                ? DatabaseStatus.DELETED : DatabaseStatus.RUNNING);
        database.setProvisioningStage(ProvisioningStage.READY);
        database.setEngine(DatabaseEngine.POSTGRESQL);
        database.setMode(DatabaseMode.STANDALONE);
        database.setDeletionProtection(false);
        return database;
    }

    private OperationMetadata deleteOperation(String operationId) {
        return OperationMetadata.builder()
                .operationId(operationId)
                .databaseId("db-orders0001")
                .projectName("orders")
                .type(OperationType.DELETE)
                .status(OperationStatus.RUNNING)
                .provisioningStage(ProvisioningStage.WAITING_FOR_REPLICAS)
                .progress(50)
                .createdAt(java.time.Instant.now())
                .updatedAt(java.time.Instant.now())
                .build();
    }
}
