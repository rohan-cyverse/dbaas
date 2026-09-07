package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.config.DatabaseProperties;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import io.kubernetes.client.openapi.ApiClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class SharedGatewayServiceTest {

    @Test
    void disabledInstancesNeverAcquireTheGatewayLock() {
        DatabaseProperties properties = new DatabaseProperties();
        GatewayReconciliationLock lock = mock(GatewayReconciliationLock.class);
        SharedGatewayService service = service(properties, lock);

        assertFalse(properties.getGateway().isReconcileEnabled());
        service.reconcileNow();
        service.scheduledReconcile();

        verifyNoInteractions(lock);
    }

    @Test
    void enabledInstancesReconcileThroughTheMySqlLock() {
        DatabaseProperties properties = new DatabaseProperties();
        properties.getGateway().setReconcileEnabled(true);
        GatewayReconciliationLock lock = mock(GatewayReconciliationLock.class);
        SharedGatewayService service = service(properties, lock);

        service.reconcileNow();

        verify(lock).execute(any(Runnable.class));
    }

    @Test
    void disabledInstanceCanRemoveMetadataRouteWithoutWaitingForGatewayRollout() {
        DatabaseProperties properties = new DatabaseProperties();
        GatewayReconciliationLock lock = mock(GatewayReconciliationLock.class);
        DatabaseMetadataRepository repository = mock(DatabaseMetadataRepository.class);
        SharedGatewayService service = service(properties, lock, repository);
        DatabaseMetadata database = new DatabaseMetadata();
        database.setDatabaseId("db-123456789012");
        database.setPublicPort(31000);

        service.removeRoute(database);

        verify(repository).save(database);
        verifyNoInteractions(lock);
    }

    private SharedGatewayService service(DatabaseProperties properties, GatewayReconciliationLock lock) {
        return service(properties, lock, mock(DatabaseMetadataRepository.class));
    }

    private SharedGatewayService service(DatabaseProperties properties, GatewayReconciliationLock lock,
                                         DatabaseMetadataRepository repository) {
        return new SharedGatewayService(properties, repository, mock(PublicPortAllocator.class),
                mock(KubeBlocksClient.class), new ApiClient(), lock, mock(DatabaseBackendResolver.class));
    }
}
