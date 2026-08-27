package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.dto.HorizontalScalingRequest;
import com.cyfuture.dbaas.dto.RestartRequest;
import com.cyfuture.dbaas.dto.StorageExpansionRequest;
import com.cyfuture.dbaas.dto.VerticalScalingRequest;
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
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import com.cyfuture.dbaas.repository.OperationMetadataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseOperationServiceTest {
    private KubeBlocksClient kubeBlocksClient;
    private DatabaseMetadataRepository databaseRepository;
    private OperationMetadataRepository operationRepository;
    private ProjectService projectService;
    private KubeBlocksOperationSubmitter submitter;
    private DatabaseOperationService service;
    private DatabaseMetadata database;

    @BeforeEach
    void setUp() {
        kubeBlocksClient = mock(KubeBlocksClient.class);
        databaseRepository = mock(DatabaseMetadataRepository.class);
        operationRepository = mock(OperationMetadataRepository.class);
        projectService = mock(ProjectService.class);
        submitter = mock(KubeBlocksOperationSubmitter.class);
        service = new DatabaseOperationService(kubeBlocksClient, databaseRepository,
                operationRepository, projectService, new OperationMapper(), submitter);

        ProjectMetadata project = new ProjectMetadata();
        project.setProjectName("orders");
        project.setNamespaceName("dbaas-orders");
        when(projectService.requireActiveProject("orders")).thenReturn(project);

        database = new DatabaseMetadata();
        database.setDatabaseId("db-orders0001");
        database.setProjectName("orders");
        database.setNamespaceName("dbaas-orders");
        database.setEngine(DatabaseEngine.POSTGRESQL);
        database.setMode(DatabaseMode.REPLICATION);
        database.setStatus(DatabaseStatus.RUNNING);
        database.setProvisioningStage(ProvisioningStage.READY);

        when(databaseRepository.findByDatabaseIdAndProjectNameForUpdate("db-orders0001", "orders"))
                .thenReturn(Optional.of(database));
        when(databaseRepository.findByDatabaseIdAndProjectName("db-orders0001", "orders"))
                .thenReturn(Optional.of(database));
        when(operationRepository.findByDatabaseIdAndProjectNameAndIdempotencyKey(
                anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(operationRepository.findByDatabaseIdAndProjectNameAndStatusIn(
                "db-orders0001", "orders", List.of(OperationStatus.PENDING, OperationStatus.RUNNING)))
                .thenReturn(List.of());
        when(kubeBlocksClient.requireComponent("dbaas-orders", "db-orders0001", "postgresql"))
                .thenReturn(new KubeBlocksClient.ClusterComponentInfo(
                        "postgresql", 2, 0, false, Map.of("data", "20Gi")));
        when(kubeBlocksClient.storageBytes("20Gi")).thenReturn(20L * 1024 * 1024 * 1024);
        when(kubeBlocksClient.storageBytes("30Gi")).thenReturn(30L * 1024 * 1024 * 1024);
        when(kubeBlocksClient.storageBytes("10Gi")).thenReturn(10L * 1024 * 1024 * 1024);
    }

    @Test
    void queuesHorizontalScalingAndSubmitsAsync() {
        var response = service.horizontalScaling("orders", "db-orders0001",
                "scale-replicas-001", new HorizontalScalingRequest("postgresql", 3));

        ArgumentCaptor<OperationMetadata> saved = ArgumentCaptor.forClass(OperationMetadata.class);
        verify(operationRepository).save(saved.capture());
        assertEquals(OperationType.HORIZONTAL_SCALING, saved.getValue().getType());
        assertEquals("postgresql", saved.getValue().getComponentName());
        assertEquals(3, saved.getValue().getTargetReplicas());
        assertEquals(OperationStatus.PENDING, response.status());
        verify(submitter).submit(saved.getValue().getOperationId());
    }

    @Test
    void duplicateIdempotencyKeyReturnsExistingOperation() {
        OperationMetadata existing = operation(OperationType.RESTART, "restart-orders-001",
                "restart|");
        when(operationRepository.findByDatabaseIdAndProjectNameAndIdempotencyKey(
                "db-orders0001", "orders", "restart-orders-001"))
                .thenReturn(Optional.of(existing));

        var response = service.restart("orders", "db-orders0001",
                "restart-orders-001", new RestartRequest(null));

        assertEquals(existing.getOperationId(), response.operationId());
        verify(submitter, never()).submit(anyString());
    }

    @Test
    void rejectsConflictingActiveOperation() {
        when(operationRepository.findByDatabaseIdAndProjectNameAndStatusIn(
                "db-orders0001", "orders", List.of(OperationStatus.PENDING, OperationStatus.RUNNING)))
                .thenReturn(List.of(operation(OperationType.RESTART, "restart-orders-001", "restart||")));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.horizontalScaling("orders", "db-orders0001",
                        "scale-replicas-001", new HorizontalScalingRequest("postgresql", 3)));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
    }

    @Test
    void rejectsStorageShrink() {
        ApiException exception = assertThrows(ApiException.class,
                () -> service.storageExpansion("orders", "db-orders0001",
                        "expand-storage-001",
                        new StorageExpansionRequest("postgresql", "data", "10Gi")));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
    }

    @Test
    void rejectsVerticalRequestAboveLimit() {
        ApiException exception = assertThrows(ApiException.class,
                () -> service.verticalScaling("orders", "db-orders0001",
                        "scale-compute-001",
                        new VerticalScalingRequest("postgresql",
                                new VerticalScalingRequest.ResourceValues("2", "4Gi"),
                                new VerticalScalingRequest.ResourceValues("1", "2Gi"))));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
    }

    private OperationMetadata operation(OperationType type, String key, String hashSource) {
        return OperationMetadata.builder()
                .operationId("op-existing001")
                .databaseId("db-orders0001")
                .projectName("orders")
                .type(type)
                .status(OperationStatus.RUNNING)
                .provisioningStage(ProvisioningStage.WAITING_FOR_REPLICAS)
                .progress(50)
                .idempotencyKey(key)
                .requestHash(hash(hashSource))
                .message("running")
                .createdAt(Instant.now())
                .build();
    }

    private String hash(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
