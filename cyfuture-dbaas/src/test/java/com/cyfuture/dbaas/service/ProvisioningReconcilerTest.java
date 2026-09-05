package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.client.DatabaseObservation;
import com.cyfuture.dbaas.dto.PublicEndpointResponse;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.model.DatabaseEngine;
import com.cyfuture.dbaas.model.DatabaseMode;
import com.cyfuture.dbaas.model.DatabaseStatus;
import com.cyfuture.dbaas.model.ProvisioningStage;
import com.cyfuture.dbaas.model.SizePlan;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProvisioningReconcilerTest {
    private final DatabaseMetadataRepository repository =
            mock(DatabaseMetadataRepository.class);
    private final KubeBlocksClient client = mock(KubeBlocksClient.class);
    private final CredentialLifecycleService credentials =
            mock(CredentialLifecycleService.class);
    private final ProvisioningProgressService progress =
            mock(ProvisioningProgressService.class);
    private final SharedGatewayService gateway = mock(SharedGatewayService.class);
    private final ProvisioningReconciler reconciler = new ProvisioningReconciler(
            repository, client, credentials, progress, gateway);

    @Test
    void completesOnlyAfterDatabaseCredentialsAndPublicEndpointAreReady() {
        DatabaseMetadata database = database();
        List<String> cidrs = List.of("49.50.73.146/32");
        when(client.get(database.getNamespaceName(), database.getDatabaseId()))
                .thenReturn(live(database, DatabaseStatus.RUNNING));
        when(credentials.ready(database)).thenReturn(true);
        when(gateway.configure(database)).thenReturn(
                new PublicEndpointResponse("49.50.116.140", 31000, true, cidrs));

        reconciler.reconcile(database);

        verify(progress).ready(database);
    }

    @Test
    void keepsWaitingWhileReplicasAreNotReady() {
        DatabaseMetadata database = database();
        when(client.get(database.getNamespaceName(), database.getDatabaseId()))
                .thenReturn(live(database, DatabaseStatus.PROVISIONING));

        reconciler.reconcile(database);

        verify(progress).update(database, ProvisioningStage.WAITING_FOR_REPLICAS,
                45, "Waiting for database Pods: 0/1");
    }

    private DatabaseMetadata database() {
        DatabaseMetadata database = new DatabaseMetadata();
        database.setDatabaseId("db-123456789012");
        database.setOperationId("op-123456789012");
        database.setProjectName("test-project");
        database.setNamespaceName("dbaas-cyfuture-test-project");
        database.setEngine(DatabaseEngine.POSTGRESQL);
        database.setMode(DatabaseMode.STANDALONE);
        database.setAllowedCidrs("[49.50.73.146/32]");
        database.setStatus(DatabaseStatus.PROVISIONING);
        database.setProvisioningStage(ProvisioningStage.WAITING_FOR_REPLICAS);
        return database;
    }

    private DatabaseObservation live(DatabaseMetadata database, DatabaseStatus status) {
        boolean ready = status == DatabaseStatus.RUNNING;
        return new DatabaseObservation(database.getDatabaseId(), "orders-db",
                database.getEngine(), database.getMode(), "17.5.0", SizePlan.C1G2,
                20, true, status, 1, ready ? 1 : 0, ready ? 1 : 0, ready,
                ready ? "postgres.internal" : null, 5432,
                ready ? "Database is ready" : "Waiting for database Pods: 0/1");
    }
}
