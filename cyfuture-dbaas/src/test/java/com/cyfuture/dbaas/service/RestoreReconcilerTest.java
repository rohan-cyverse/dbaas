package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.dto.PublicEndpointResponse;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.entity.OperationMetadata;
import com.cyfuture.dbaas.entity.RestoreRequestMetadata;
import com.cyfuture.dbaas.exception.ApiException;
import com.cyfuture.dbaas.model.DatabaseStatus;
import com.cyfuture.dbaas.model.OperationStatus;
import com.cyfuture.dbaas.model.RestoreStatus;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RestoreReconcilerTest {
    private RestoreRequestMetadataRepository restores;
    private DatabaseMetadataRepository databases;
    private OperationMetadataRepository operations;
    private KubeBlocksClient kubeBlocks;
    private CredentialLifecycleService credentials;
    private SharedGatewayService gateway;
    private ProvisioningProgressService progress;
    private RestoreReconciler reconciler;
    private RestoreRequestMetadata restore;
    private DatabaseMetadata target;

    @BeforeEach
    void setUp() {
        restores = mock(RestoreRequestMetadataRepository.class);
        databases = mock(DatabaseMetadataRepository.class);
        operations = mock(OperationMetadataRepository.class);
        kubeBlocks = mock(KubeBlocksClient.class);
        credentials = mock(CredentialLifecycleService.class);
        gateway = mock(SharedGatewayService.class);
        progress = mock(ProvisioningProgressService.class);
        reconciler = new RestoreReconciler(restores, databases, operations, kubeBlocks,
                credentials, gateway, progress, mock(RestoreSubmissionService.class));
        restore = new RestoreRequestMetadata();
        restore.setRestoreId("rst-orders");
        restore.setOperationId("op-restore");
        restore.setProjectName("prj-orders");
        restore.setSourceDatabaseId("db-orders");
        restore.setRestoredDatabaseId("db-restored");
        restore.setRestoredDatabaseName("appdb_orders");
        restore.setKubernetesOpsRequestName("rst-orders");
        restore.setStatus(RestoreStatus.RUNNING);
        target = new DatabaseMetadata();
        target.setProjectName("prj-orders");
        target.setDatabaseId("db-restored");
        target.setDisplayName("restored-orders");
        target.setNamespaceName("dbaas-orders");
        target.setStatus(DatabaseStatus.PROVISIONING);
        when(databases.findByDatabaseIdAndProjectName("db-restored", "prj-orders"))
                .thenReturn(Optional.of(target));
        when(kubeBlocks.getOpsRequest("dbaas-orders", "rst-orders"))
                .thenReturn(new KubeBlocksClient.OpsRequestInfo("Succeed", "1/1", "done", null, null, null));
        when(kubeBlocks.observeRestore("dbaas-orders", "rst-orders", null))
                .thenReturn(new KubeBlocksClient.RestoreObservation(true, "restore-cr", "Completed", "done",
                        null, null));
        when(kubeBlocks.observeCluster("dbaas-orders", "db-restored"))
                .thenReturn(new KubeBlocksClient.ClusterObservation(true, "dbaas-orders", "db-restored",
                        "Running", 2, 2, true, "ready"));
        OperationMetadata operation = OperationMetadata.builder().operationId("op-restore")
                .status(OperationStatus.RUNNING).build();
        when(operations.findById("op-restore")).thenReturn(Optional.of(operation));
    }

    @Test
    void doesNotPublishRunningUntilRestoredCredentialsAreReady() {
        when(credentials.readyForRestoredDatabase(target, "appdb_orders", "dbaas_orders")).thenReturn(false);

        reconciler.reconcile(restore);

        assertEquals(RestoreStatus.RUNNING, restore.getStatus());
        verify(gateway, never()).configure(target);
        verify(progress, never()).ready(target);
    }

    @Test
    void completesOnlyAfterRestoreCrClusterCredentialsAndPublicEndpointAreReady() {
        when(credentials.readyForRestoredDatabase(target, "appdb_orders", "dbaas_orders")).thenReturn(true);
        when(credentials.databaseName(target)).thenReturn("appdb_orders");
        when(gateway.configure(target)).thenReturn(new PublicEndpointResponse(
                "db.example.com", 31432, true, List.of("203.0.113.10/32")));

        reconciler.reconcile(restore);

        assertEquals(RestoreStatus.COMPLETED, restore.getStatus());
        assertEquals("restore-cr", restore.getKubernetesRestoreName());
        assertEquals("appdb_orders", restore.getRestoredDatabaseName());
        assertEquals("db.example.com", restore.getPublicHost());
        assertEquals(31432, restore.getPublicPort());
        verify(credentials).readyForRestoredDatabase(target, "appdb_orders", "dbaas_orders");
        verify(credentials).databaseName(target);
        verify(progress).ready(target);
        verify(operations).save(org.mockito.ArgumentMatchers.any(OperationMetadata.class));
    }

    @Test
    void marksRestoreFailedWhenTheRestoredLogicalDatabaseCannotBeValidated() {
        restore.setCreatedAt(Instant.now());
        when(restores.findByStatusInOrderByCreatedAtAsc(List.of(RestoreStatus.PENDING, RestoreStatus.RUNNING)))
                .thenReturn(List.of(restore));
        when(credentials.readyForRestoredDatabase(target, "appdb_orders", "dbaas_orders"))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "RESTORED_DATABASE_VALIDATION_FAILED", false,
                        "Restored logical database validation or credential setup failed."));

        reconciler.reconcile();

        assertEquals(RestoreStatus.FAILED, restore.getStatus());
        verify(restores, org.mockito.Mockito.atLeastOnce()).save(restore);
    }
}
