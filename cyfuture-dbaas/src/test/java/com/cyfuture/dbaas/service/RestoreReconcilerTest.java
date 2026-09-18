package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.dto.PublicEndpointResponse;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.entity.OperationMetadata;
import com.cyfuture.dbaas.entity.RestoreRequestMetadata;
import com.cyfuture.dbaas.model.DatabaseEngine;
import com.cyfuture.dbaas.model.DatabaseMode;
import com.cyfuture.dbaas.model.DatabaseStatus;
import com.cyfuture.dbaas.model.OperationStatus;
import com.cyfuture.dbaas.model.OperationType;
import com.cyfuture.dbaas.model.ProvisioningStage;
import com.cyfuture.dbaas.model.RestoreMode;
import com.cyfuture.dbaas.model.RestoreStatus;
import com.cyfuture.dbaas.repository.BackupMetadataRepository;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import com.cyfuture.dbaas.repository.OperationMetadataRepository;
import com.cyfuture.dbaas.repository.RestoreRequestMetadataRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RestoreReconcilerTest {

    @Test
    void successfulRestoreCutsStableDatabaseOverToTemporaryCluster() {
        Fixture fixture = fixture();
        RestoreRequestMetadata restore = restore(RestoreStatus.RESTORING);
        DatabaseMetadata database = database();
        OperationMetadata operation = operation();
        when(fixture.databaseRepository.findByDatabaseIdAndProjectName("db-orders0001", "orders"))
                .thenReturn(Optional.of(database));
        when(fixture.operationRepository.findById("op-restore0001")).thenReturn(Optional.of(operation));
        when(fixture.kubeBlocksClient.getOpsRequest("dbaas-orders", "rst-restore0001"))
                .thenReturn(new KubeBlocksClient.OpsRequestInfo("Succeed", "1/1", "done",
                        Instant.now(), Instant.now()));
        when(fixture.kubeBlocksClient.observeRestore(any(), any(), any()))
                .thenReturn(new KubeBlocksClient.RestoreObservation(true, "restore-cr",
                        "Completed", "done", Instant.now(), Instant.now()));
        when(fixture.kubeBlocksClient.observeCluster("dbaas-orders", "db-orders0001-restore-abc12345"))
                .thenReturn(new KubeBlocksClient.ClusterObservation(true, "dbaas-orders",
                        "db-orders0001-restore-abc12345", "Running", 2, 2, true, "ready"));
        when(fixture.credentialLifecycleService.readyForRestoredCluster(any(), any(), any(), any()))
                .thenReturn(true);
        when(fixture.credentialLifecycleService.databaseName(database))
                .thenReturn(CredentialLifecycleService.managedDatabaseName("db-orders0001"));
        when(fixture.sharedGatewayService.configure(database))
                .thenReturn(new PublicEndpointResponse("203.0.113.10", 32000, true, List.of("0.0.0.0/0")));

        fixture.reconciler.refresh(restore);
        assertEquals(RestoreStatus.CUTTING_OVER, restore.getStatus());
        assertEquals(DatabaseStatus.MAINTENANCE, database.getStatus());

        fixture.reconciler.refresh(restore);

        assertEquals(RestoreStatus.COMPLETED, restore.getStatus());
        assertEquals("db-orders0001-restore-abc12345", database.getActiveClusterName());
        assertEquals(OperationStatus.SUCCEEDED, operation.getStatus());
        assertNotNull(restore.getOldClusterDeleteAt());
        verify(fixture.sharedGatewayService).configure(database);
    }

    @Test
    void failedOpsRequestFailsRestoreAndOperationWithActualReason() {
        Fixture fixture = fixture();
        RestoreRequestMetadata restore = restore(RestoreStatus.RESTORING);
        DatabaseMetadata database = database();
        OperationMetadata operation = operation();
        when(fixture.databaseRepository.findByDatabaseIdAndProjectName("db-orders0001", "orders"))
                .thenReturn(Optional.of(database));
        when(fixture.operationRepository.findById("op-restore0001")).thenReturn(Optional.of(operation));
        when(fixture.kubeBlocksClient.getOpsRequest("dbaas-orders", "rst-restore0001"))
                .thenReturn(new KubeBlocksClient.OpsRequestInfo("Failed", "1/1",
                        "target cluster already exists", "AlreadyExists", Instant.now(), Instant.now()));

        fixture.reconciler.refresh(restore);

        assertEquals(RestoreStatus.FAILED, restore.getStatus());
        assertEquals("AlreadyExists", restore.getFailureCode());
        assertEquals(OperationStatus.FAILED, operation.getStatus());
        verify(fixture.kubeBlocksClient).requestDelete("dbaas-orders", "db-orders0001-restore-abc12345");
    }

    private Fixture fixture() {
        RestoreRequestMetadataRepository restoreRepository = mock(RestoreRequestMetadataRepository.class);
        DatabaseMetadataRepository databaseRepository = mock(DatabaseMetadataRepository.class);
        OperationMetadataRepository operationRepository = mock(OperationMetadataRepository.class);
        BackupMetadataRepository backupRepository = mock(BackupMetadataRepository.class);
        KubeBlocksClient kubeBlocksClient = mock(KubeBlocksClient.class);
        CredentialLifecycleService credentialLifecycleService = mock(CredentialLifecycleService.class);
        SharedGatewayService sharedGatewayService = mock(SharedGatewayService.class);
        ProvisioningProgressService progressService = mock(ProvisioningProgressService.class);
        RestoreSubmissionService submissionService = mock(RestoreSubmissionService.class);
        OperationService operationService = mock(OperationService.class);
        RestoreReconciler reconciler = new RestoreReconciler(restoreRepository, databaseRepository,
                operationRepository, backupRepository, kubeBlocksClient, credentialLifecycleService,
                sharedGatewayService, progressService, submissionService, operationService);
        ReflectionTestUtils.setField(reconciler, "rollbackRetentionMinutes", 60L);
        return new Fixture(reconciler, restoreRepository, databaseRepository, operationRepository,
                kubeBlocksClient, credentialLifecycleService, sharedGatewayService);
    }

    private DatabaseMetadata database() {
        DatabaseMetadata database = new DatabaseMetadata();
        database.setDatabaseId("db-orders0001");
        database.setProjectName("orders");
        database.setNamespaceName("dbaas-orders");
        database.setDisplayName("orders");
        database.setActiveClusterName("db-orders0001");
        database.setEngine(DatabaseEngine.POSTGRESQL);
        database.setMode(DatabaseMode.REPLICATION);
        database.setStatus(DatabaseStatus.RUNNING);
        database.setProvisioningStage(ProvisioningStage.READY);
        return database;
    }

    private RestoreRequestMetadata restore(RestoreStatus status) {
        RestoreRequestMetadata restore = new RestoreRequestMetadata();
        restore.setRestoreId("rst-restore0001");
        restore.setOperationId("op-restore0001");
        restore.setProjectName("orders");
        restore.setSourceDatabaseId("db-orders0001");
        restore.setSourceBackupId("bkp-orders0001");
        restore.setRestoreMode(RestoreMode.FULL);
        restore.setRestoredDatabaseId("db-orders0001");
        restore.setTemporaryClusterName("db-orders0001-restore-abc12345");
        restore.setOldClusterName("db-orders0001");
        restore.setKubernetesOpsRequestName("rst-restore0001");
        restore.setTargetDatabaseName("orders");
        restore.setStatus(status);
        restore.setTemporary(true);
        restore.setCreatedAt(Instant.now());
        return restore;
    }

    private OperationMetadata operation() {
        return OperationMetadata.builder()
                .operationId("op-restore0001")
                .databaseId("db-orders0001")
                .projectName("orders")
                .type(OperationType.RESTORE)
                .status(OperationStatus.RUNNING)
                .provisioningStage(ProvisioningStage.RESTORING_DATA)
                .progress(40)
                .createdAt(Instant.now())
                .build();
    }

    private record Fixture(RestoreReconciler reconciler,
                           RestoreRequestMetadataRepository restoreRepository,
                           DatabaseMetadataRepository databaseRepository,
                           OperationMetadataRepository operationRepository,
                           KubeBlocksClient kubeBlocksClient,
                           CredentialLifecycleService credentialLifecycleService,
                           SharedGatewayService sharedGatewayService) {}
}
