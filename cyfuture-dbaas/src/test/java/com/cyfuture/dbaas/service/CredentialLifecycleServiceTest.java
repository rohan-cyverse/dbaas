package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.config.DatabaseProperties;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.mapper.OperationMapper;
import com.cyfuture.dbaas.repository.OperationMetadataRepository;
import io.kubernetes.client.openapi.apis.BatchV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1JobList;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.openapi.models.V1Secret;
import io.kubernetes.client.openapi.models.V1SecretList;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
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

    private DatabaseMetadata database() {
        DatabaseMetadata database = new DatabaseMetadata();
        database.setDatabaseId("db-orders0001");
        database.setProjectName("prj-orders0001");
        database.setNamespaceName("dbaas-orders");
        return database;
    }
}
