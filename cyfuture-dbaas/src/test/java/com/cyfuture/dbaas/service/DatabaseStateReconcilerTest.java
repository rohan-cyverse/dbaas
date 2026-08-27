package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.entity.OperationMetadata;
import com.cyfuture.dbaas.exception.ApiException;
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
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseStateReconcilerTest {
    private DatabaseMetadataRepository databaseRepository;
    private OperationMetadataRepository operationRepository;
    private KubeBlocksClient kubeBlocksClient;
    private SharedGatewayService gateway;
    private DatabaseStateReconciler reconciler;

    @BeforeEach
    void setUp() {
        databaseRepository = mock(DatabaseMetadataRepository.class);
        operationRepository = mock(OperationMetadataRepository.class);
        kubeBlocksClient = mock(KubeBlocksClient.class);
        gateway = mock(SharedGatewayService.class);
        reconciler = new DatabaseStateReconciler(databaseRepository, operationRepository,
                kubeBlocksClient, gateway, mock(JdbcTemplate.class));
        ReflectionTestUtils.setField(reconciler, "degradedGraceMs", 1L);
        ReflectionTestUtils.setField(reconciler, "missingGraceMs", 1L);
    }

    @Test
    void restoresRunningAfterPodRecovery() {
        DatabaseMetadata database = database(DatabaseStatus.DEGRADED);
        database.setDegradedSince(Instant.now().minusSeconds(60));
        when(kubeBlocksClient.observeCluster("dbaas-orders", "db-orders0001"))
                .thenReturn(observed(true, 2, 2, true));

        reconciler.reconcile(database);

        assertEquals(DatabaseStatus.RUNNING, database.getStatus());
        assertNull(database.getDegradedSince());
        verify(databaseRepository).save(database);
    }

    @Test
    void marksMissingAfterClusterGraceAndRemovesRoute() {
        DatabaseMetadata database = database(DatabaseStatus.RUNNING);
        database.setMissingSince(Instant.now().minusSeconds(60));
        when(kubeBlocksClient.observeCluster("dbaas-orders", "db-orders0001"))
                .thenReturn(KubeBlocksClient.ClusterObservation.missing(
                        "dbaas-orders", "db-orders0001"));

        reconciler.reconcile(database);

        assertEquals(DatabaseStatus.MISSING, database.getStatus());
        verify(gateway).removeRoute(database);
        verify(databaseRepository).save(database);
    }

    @Test
    void deletionConfirmsAbsenceBeforeReleasingPortAndMarkingDeleted() {
        DatabaseMetadata database = database(DatabaseStatus.DELETING);
        database.setPublicPort(31000);
        OperationMetadata operation = deleteOperation();
        when(kubeBlocksClient.observeCluster("dbaas-orders", "db-orders0001"))
                .thenReturn(KubeBlocksClient.ClusterObservation.missing(
                        "dbaas-orders", "db-orders0001"));
        when(operationRepository.findByDatabaseIdAndProjectNameAndStatusIn(
                "db-orders0001", "orders", List.of(OperationStatus.PENDING, OperationStatus.RUNNING)))
                .thenReturn(List.of(operation));

        reconciler.reconcile(database);

        verify(gateway).removeRoute(database);
        verify(kubeBlocksClient).requestDelete("dbaas-orders", "db-orders0001");
        verify(gateway).releasePort(database);
        assertEquals(DatabaseStatus.DELETED, database.getStatus());
        assertEquals(OperationStatus.SUCCEEDED, operation.getStatus());
        verify(operationRepository).save(operation);
    }

    @Test
    void kubernetesFailureDuringDeletionDoesNotMarkDeletedOrReleasePort() {
        DatabaseMetadata database = database(DatabaseStatus.DELETING);
        org.mockito.Mockito.doThrow(new ApiException(HttpStatus.BAD_GATEWAY,
                        "Kubernetes timeout"))
                .when(kubeBlocksClient).requestDelete("dbaas-orders", "db-orders0001");

        reconciler.reconcile(database);

        assertEquals(DatabaseStatus.DELETING, database.getStatus());
        verify(gateway, never()).releasePort(any());
        verify(databaseRepository).save(database);
    }

    private DatabaseMetadata database(DatabaseStatus status) {
        DatabaseMetadata database = new DatabaseMetadata();
        database.setDatabaseId("db-orders0001");
        database.setOperationId("op-create0001");
        database.setProjectName("orders");
        database.setNamespaceName("dbaas-orders");
        database.setEngine(DatabaseEngine.POSTGRESQL);
        database.setMode(DatabaseMode.REPLICATION);
        database.setDesiredStatus(DatabaseStatus.RUNNING);
        database.setStatus(status);
        database.setProvisioningStage(ProvisioningStage.READY);
        database.setExpectedReplicas(2);
        database.setObservedReadyReplicas(2);
        database.setObservedServiceReady(true);
        database.setCreatedAt(Instant.now());
        database.setUpdatedAt(Instant.now());
        return database;
    }

    private KubeBlocksClient.ClusterObservation observed(boolean exists, int ready,
                                                        int expected,
                                                        boolean serviceReady) {
        return new KubeBlocksClient.ClusterObservation(exists, "dbaas-orders",
                "db-orders0001", exists ? "Running" : "Missing",
                ready, expected, serviceReady, "observed");
    }

    private OperationMetadata deleteOperation() {
        return OperationMetadata.builder()
                .operationId("op-delete0001")
                .databaseId("db-orders0001")
                .projectName("orders")
                .type(OperationType.DELETE)
                .status(OperationStatus.RUNNING)
                .provisioningStage(ProvisioningStage.WAITING_FOR_REPLICAS)
                .progress(50)
                .createdAt(Instant.now())
                .build();
    }
}
