package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.client.DatabaseObservation;
import com.cyfuture.dbaas.config.DatabaseProperties;
import com.cyfuture.dbaas.dto.OperationResponse;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.entity.OperationMetadata;
import com.cyfuture.dbaas.mapper.OperationMapper;
import com.cyfuture.dbaas.model.DatabaseEngine;
import com.cyfuture.dbaas.model.DatabaseMode;
import com.cyfuture.dbaas.model.DatabaseStatus;
import com.cyfuture.dbaas.model.OperationStatus;
import com.cyfuture.dbaas.model.OperationType;
import com.cyfuture.dbaas.model.ProvisioningStage;
import com.cyfuture.dbaas.model.SizePlan;
import com.cyfuture.dbaas.repository.OperationMetadataRepository;
import io.kubernetes.client.openapi.apis.BatchV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1JobList;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.openapi.models.V1PodStatus;
import io.kubernetes.client.openapi.models.V1Secret;
import io.kubernetes.client.openapi.models.V1SecretList;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CredentialLifecycleServiceTest {
    @Test
    void removesOnlyDatabaseSpecificCredentialHelpersAndLeavesSharedResourcesAlone() throws Exception {
        CoreV1Api core = mock(CoreV1Api.class, RETURNS_DEEP_STUBS);
        BatchV1Api batch = mock(BatchV1Api.class, RETURNS_DEEP_STUBS);
        CredentialLifecycleService service = new CredentialLifecycleService(
                mock(KubeBlocksClient.class), new DatabaseProperties(),
                mock(OperationMetadataRepository.class), new OperationMapper(), core, batch);
        DatabaseMetadata database = database();

        V1Job helperJob = new V1Job().metadata(new V1ObjectMeta().name("db-orders0001-credentials-1"));
        V1Pod helperPod = new V1Pod().metadata(new V1ObjectMeta().name("db-orders0001-credentials-1-x"));
        V1Secret helperSecret = new V1Secret().metadata(new V1ObjectMeta().name("db-orders0001-managed-credentials"));
        V1Secret sharedSecret = new V1Secret().metadata(new V1ObjectMeta().name("shared-credential-service"));
        when(batch.listNamespacedJob("dbaas-orders").execute())
                .thenReturn(new V1JobList().items(List.of(helperJob)), new V1JobList().items(List.of()));
        when(core.listNamespacedPod("dbaas-orders").execute())
                .thenReturn(new V1PodList().items(List.of(helperPod)), new V1PodList().items(List.of()));
        when(core.listNamespacedSecret("dbaas-orders").execute())
                .thenReturn(new V1SecretList().items(List.of(helperSecret, sharedSecret)),
                        new V1SecretList().items(List.of(sharedSecret)));

        CredentialLifecycleService.CredentialCleanupObservation result =
                service.cleanupDatabaseResources(database);

        assertTrue(result.complete());
        verify(batch).deleteNamespacedJob("db-orders0001-credentials-1", "dbaas-orders");
        verify(core).deleteNamespacedPod("db-orders0001-credentials-1-x", "dbaas-orders");
        verify(core).deleteNamespacedSecret("db-orders0001-managed-credentials", "dbaas-orders");
    }

    @Test
    void returnsTheExistingRotationWhenCredentialsAreAlreadyPending() throws Exception {
        CoreV1Api core = mock(CoreV1Api.class, RETURNS_DEEP_STUBS);
        BatchV1Api batch = mock(BatchV1Api.class, RETURNS_DEEP_STUBS);
        OperationMetadataRepository operations = mock(OperationMetadataRepository.class);
        CredentialLifecycleService service = new CredentialLifecycleService(
                mock(KubeBlocksClient.class), new DatabaseProperties(), operations,
                new OperationMapper(), core, batch);
        V1Secret secret = new V1Secret().metadata(new V1ObjectMeta()
                .name("db-orders0001-managed-credentials")
                .annotations(new LinkedHashMap<>(Map.of(
                        "dbaas.cyfuture.com/credential-status", "PENDING",
                        "dbaas.cyfuture.com/credential-operation-id", "op-rotate0001"))));
        OperationMetadata operation = OperationMetadata.builder()
                .operationId("op-rotate0001")
                .databaseId("db-orders0001")
                .projectName("prj-orders0001")
                .type(OperationType.ROTATE_CREDENTIALS)
                .status(OperationStatus.RUNNING)
                .provisioningStage(ProvisioningStage.CREATING_CREDENTIALS)
                .progress(50)
                .createdAt(Instant.now())
                .build();
        when(core.readNamespacedSecret("db-orders0001-managed-credentials", "dbaas-orders")
                .execute()).thenReturn(secret);
        when(operations.findById("op-rotate0001")).thenReturn(Optional.of(operation));

        OperationResponse response = service.rotate(database());

        assertEquals("op-rotate0001", response.operationId());
        assertEquals(OperationStatus.RUNNING, response.status());
        verify(operations, never()).save(any());
    }

    @Test
    void removesCompletedCredentialPodsAfterCredentialsBecomeReady() throws Exception {
        CoreV1Api core = mock(CoreV1Api.class, RETURNS_DEEP_STUBS);
        BatchV1Api batch = mock(BatchV1Api.class, RETURNS_DEEP_STUBS);
        KubeBlocksClient kubeBlocks = mock(KubeBlocksClient.class);
        CredentialLifecycleService service = new CredentialLifecycleService(
                kubeBlocks, new DatabaseProperties(), mock(OperationMetadataRepository.class),
                new OperationMapper(), core, batch);
        DatabaseMetadata database = database();
        Map<String, String> labels = Map.of(
                "app.kubernetes.io/managed-by", "cyfuture-dbaas",
                "dbaas.cyfuture.com/database-id", database.getDatabaseId(),
                "dbaas.cyfuture.com/project-id", database.getProjectName(),
                "dbaas.cyfuture.com/credential-helper", "true");
        V1Secret secret = new V1Secret().metadata(new V1ObjectMeta()
                .name("db-orders0001-managed-credentials")
                .labels(labels)
                .ownerReferences(List.of(new io.kubernetes.client.openapi.models.V1OwnerReference()
                        .kind("Cluster")))
                .annotations(new LinkedHashMap<>(Map.of(
                        "dbaas.cyfuture.com/credential-status", "READY"))));
        V1Pod completed = new V1Pod()
                .metadata(new V1ObjectMeta().name("db-orders0001-credentials-1-done").labels(labels))
                .status(new V1PodStatus().phase("Succeeded"));
        when(kubeBlocks.get("dbaas-orders", "db-orders0001")).thenReturn(new DatabaseObservation(
                "db-orders0001", "orders", DatabaseEngine.POSTGRESQL, DatabaseMode.STANDALONE,
                "17.5.0", SizePlan.C1G1, 10, false, DatabaseStatus.RUNNING,
                1, 1, 1, true, "orders", 5432, "ready"));
        when(core.readNamespacedSecret("db-orders0001-managed-credentials", "dbaas-orders")
                .execute()).thenReturn(secret);
        when(batch.listNamespacedJob("dbaas-orders").execute())
                .thenReturn(new V1JobList().items(List.of()));
        when(core.listNamespacedPod("dbaas-orders").execute())
                .thenReturn(new V1PodList().items(List.of(completed)));

        service.reconcile(database);

        verify(core).deleteNamespacedPod("db-orders0001-credentials-1-done", "dbaas-orders");
    }

    private DatabaseMetadata database() {
        DatabaseMetadata database = new DatabaseMetadata();
        database.setDatabaseId("db-orders0001");
        database.setProjectName("prj-orders0001");
        database.setNamespaceName("dbaas-orders");
        return database;
    }
}
