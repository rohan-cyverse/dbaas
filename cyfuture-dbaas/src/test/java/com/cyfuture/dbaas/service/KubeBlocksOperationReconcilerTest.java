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

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
