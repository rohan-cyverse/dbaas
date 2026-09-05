package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.entity.OperationMetadata;
import com.cyfuture.dbaas.model.DatabaseEngine;
import com.cyfuture.dbaas.model.DatabaseMode;
import com.cyfuture.dbaas.model.DatabaseStatus;
import com.cyfuture.dbaas.model.OperationStatus;
import com.cyfuture.dbaas.model.OperationType;
import com.cyfuture.dbaas.model.ProvisioningStage;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import com.cyfuture.dbaas.repository.OperationMetadataRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

class KubeBlocksOperationReconcilerTest {
    @Test
    void failedOpsRequestFailsOperationButLeavesDatabaseRunning() {
        OperationMetadataRepository operationRepository = mock(OperationMetadataRepository.class);
        DatabaseMetadataRepository databaseRepository = mock(DatabaseMetadataRepository.class);
        KubeBlocksClient kubeBlocksClient = mock(KubeBlocksClient.class);
        KubeBlocksOperationReconciler reconciler = new KubeBlocksOperationReconciler(
                operationRepository, databaseRepository, kubeBlocksClient);

        DatabaseMetadata database = database();
        OperationMetadata operation = operation();
        when(databaseRepository.findByDatabaseIdAndProjectName("db-orders0001", "orders"))
                .thenReturn(Optional.of(database));
        when(kubeBlocksClient.getOpsRequest("dbaas-orders", "op-scale0001"))
                .thenReturn(new KubeBlocksClient.OpsRequestInfo("Failed", "1/1",
                        "password=secret timeout", Instant.now(), Instant.now()));

        reconciler.reconcile(operation);

        assertEquals(OperationStatus.FAILED, operation.getStatus());
        assertEquals("password=****** timeout", operation.getMessage());
        assertEquals(DatabaseStatus.RUNNING, database.getStatus());
        verify(operationRepository).save(operation);
        verify(databaseRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void succeededStorageExpansionUpdatesStoredStorage() {
        OperationMetadataRepository operationRepository = mock(OperationMetadataRepository.class);
        DatabaseMetadataRepository databaseRepository = mock(DatabaseMetadataRepository.class);
        KubeBlocksClient kubeBlocksClient = mock(KubeBlocksClient.class);
        KubeBlocksOperationReconciler reconciler = new KubeBlocksOperationReconciler(
                operationRepository, databaseRepository, kubeBlocksClient);

        DatabaseMetadata database = database();
        OperationMetadata operation = operation();
        operation.setType(OperationType.STORAGE_EXPANSION);
        operation.setComponentName("postgresql");
        operation.setTargetStorageSize("30Gi");
        when(databaseRepository.findByDatabaseIdAndProjectName("db-orders0001", "orders"))
                .thenReturn(Optional.of(database));
        when(kubeBlocksClient.getOpsRequest("dbaas-orders", "op-scale0001"))
                .thenReturn(new KubeBlocksClient.OpsRequestInfo("Succeed", "1/1",
                        "done", Instant.now(), Instant.now()));
        when(kubeBlocksClient.storageGi("30Gi")).thenReturn(30);

        reconciler.reconcile(operation);

        assertEquals(OperationStatus.SUCCEEDED, operation.getStatus());
        assertEquals(30, database.getStorageGi());
        verify(databaseRepository).save(database);
    }

    @Test
    void verticalScalingWaitsForActualPodResourcesAfterOpsRequestSucceeds() {
        OperationMetadataRepository operationRepository = mock(OperationMetadataRepository.class);
        DatabaseMetadataRepository databaseRepository = mock(DatabaseMetadataRepository.class);
        KubeBlocksClient kubeBlocksClient = mock(KubeBlocksClient.class);
        KubeBlocksOperationReconciler reconciler = new KubeBlocksOperationReconciler(
                operationRepository, databaseRepository, kubeBlocksClient);
        DatabaseMetadata database = database();
        OperationMetadata operation = verticalOperation();
        when(databaseRepository.findByDatabaseIdAndProjectName("db-orders0001", "orders"))
                .thenReturn(Optional.of(database));
        when(kubeBlocksClient.getOpsRequest("dbaas-orders", "op-scale0001"))
                .thenReturn(new KubeBlocksClient.OpsRequestInfo("Succeed", "1/1", "done",
                        Instant.now(), Instant.now()));
        when(kubeBlocksClient.observeVerticalScaling(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(new KubeBlocksClient.VerticalScalingObservation(false, 2, 2, 1,
                        "Waiting for requested CPU/memory on 1/2 ready Pods"));

        reconciler.reconcile(operation);

        assertEquals(OperationStatus.RUNNING, operation.getStatus());
        assertEquals(95, operation.getProgress());
        verify(databaseRepository, never()).save(database);
    }

    @Test
    void verticalScalingCompletesOnlyAfterRequestedResourcesAreObservedForAllSupportedEngines() {
        for (DatabaseEngine engine : DatabaseEngine.values()) {
            OperationMetadataRepository operationRepository = mock(OperationMetadataRepository.class);
            DatabaseMetadataRepository databaseRepository = mock(DatabaseMetadataRepository.class);
            KubeBlocksClient kubeBlocksClient = mock(KubeBlocksClient.class);
            KubeBlocksOperationReconciler reconciler = new KubeBlocksOperationReconciler(
                    operationRepository, databaseRepository, kubeBlocksClient);
            DatabaseMetadata database = database();
            database.setEngine(engine);
            if (engine == DatabaseEngine.MONGODB) database.setMode(DatabaseMode.REPLICA_SET);
            OperationMetadata operation = verticalOperation();
            operation.setComponentName(engine == DatabaseEngine.POSTGRESQL ? "postgresql"
                    : engine == DatabaseEngine.MYSQL ? "mysql" : "mongodb");
            when(databaseRepository.findByDatabaseIdAndProjectName("db-orders0001", "orders"))
                    .thenReturn(Optional.of(database));
            when(kubeBlocksClient.getOpsRequest("dbaas-orders", "op-scale0001"))
                    .thenReturn(new KubeBlocksClient.OpsRequestInfo("Succeed", "1/1", "done",
                            Instant.now(), Instant.now()));
            when(kubeBlocksClient.observeVerticalScaling(org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.anyMap()))
                    .thenReturn(new KubeBlocksClient.VerticalScalingObservation(true, 2, 2, 2,
                            "Requested CPU/memory observed"));

            reconciler.reconcile(operation);

            assertEquals(OperationStatus.SUCCEEDED, operation.getStatus(), engine.name());
            verify(databaseRepository).save(database);
        }
    }

    @Test
    void instanceUpdateRestrictedFailsVerticalScalingImmediately() {
        OperationMetadataRepository operationRepository = mock(OperationMetadataRepository.class);
        DatabaseMetadataRepository databaseRepository = mock(DatabaseMetadataRepository.class);
        KubeBlocksClient kubeBlocksClient = mock(KubeBlocksClient.class);
        KubeBlocksOperationReconciler reconciler = new KubeBlocksOperationReconciler(
                operationRepository, databaseRepository, kubeBlocksClient);
        OperationMetadata operation = verticalOperation();
        when(databaseRepository.findByDatabaseIdAndProjectName("db-orders0001", "orders"))
                .thenReturn(Optional.of(database()));
        when(kubeBlocksClient.getOpsRequest("dbaas-orders", "op-scale0001"))
                .thenReturn(new KubeBlocksClient.OpsRequestInfo("Running", "1/2", "resize refused",
                        "InstanceUpdateRestricted", Instant.now(), null));

        reconciler.reconcile(operation);

        assertEquals(OperationStatus.FAILED, operation.getStatus());
        assertEquals(100, operation.getProgress());
    }

    @Test
    void verticalScalingTimesOutInsteadOfRunningForever() {
        OperationMetadataRepository operationRepository = mock(OperationMetadataRepository.class);
        DatabaseMetadataRepository databaseRepository = mock(DatabaseMetadataRepository.class);
        KubeBlocksClient kubeBlocksClient = mock(KubeBlocksClient.class);
        KubeBlocksOperationReconciler reconciler = new KubeBlocksOperationReconciler(
                operationRepository, databaseRepository, kubeBlocksClient);
        ReflectionTestUtils.setField(reconciler, "verticalScalingTimeoutMs", 1L);
        OperationMetadata operation = verticalOperation();
        operation.setStartedAt(Instant.now().minusSeconds(1));
        when(databaseRepository.findByDatabaseIdAndProjectName("db-orders0001", "orders"))
                .thenReturn(Optional.of(database()));
        when(kubeBlocksClient.getOpsRequest("dbaas-orders", "op-scale0001"))
                .thenReturn(new KubeBlocksClient.OpsRequestInfo("Running", "1/2", "still running",
                        Instant.now(), null));

        reconciler.reconcile(operation);

        assertEquals(OperationStatus.FAILED, operation.getStatus());
        assertEquals(100, operation.getProgress());
    }

    private OperationMetadata verticalOperation() {
        OperationMetadata operation = operation();
        operation.setType(OperationType.VERTICAL_SCALING);
        operation.setComponentName("postgresql");
        operation.setCpuRequest("1");
        operation.setMemoryRequest("2Gi");
        operation.setCpuLimit("2");
        operation.setMemoryLimit("4Gi");
        return operation;
    }

    private DatabaseMetadata database() {
        DatabaseMetadata database = new DatabaseMetadata();
        database.setDatabaseId("db-orders0001");
        database.setProjectName("orders");
        database.setNamespaceName("dbaas-orders");
        database.setEngine(DatabaseEngine.POSTGRESQL);
        database.setMode(DatabaseMode.REPLICATION);
        database.setStatus(DatabaseStatus.RUNNING);
        database.setProvisioningStage(ProvisioningStage.READY);
        database.setStorageGi(20);
        return database;
    }

    private OperationMetadata operation() {
        return OperationMetadata.builder()
                .operationId("op-scale0001")
                .opsRequestName("op-scale0001")
                .databaseId("db-orders0001")
                .projectName("orders")
                .type(OperationType.HORIZONTAL_SCALING)
                .status(OperationStatus.RUNNING)
                .provisioningStage(ProvisioningStage.WAITING_FOR_REPLICAS)
                .progress(50)
                .createdAt(Instant.now())
                .build();
    }
}
