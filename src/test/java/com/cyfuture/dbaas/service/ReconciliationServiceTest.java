package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.model.DatabaseStatus;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReconciliationServiceTest {
    @Test
    void detectsManagedClustersAbsentFromMetadataAsOrphans() {
        KubeBlocksClient kubeBlocksClient = mock(KubeBlocksClient.class);
        DatabaseMetadataRepository repository = mock(DatabaseMetadataRepository.class);
        ReconciliationService service = new ReconciliationService(kubeBlocksClient, repository);

        DatabaseMetadata known = new DatabaseMetadata();
        known.setNamespaceName("dbaas-orders");
        known.setDatabaseId("db-known0001");
        when(repository.findAll()).thenReturn(List.of(known));
        when(kubeBlocksClient.listManagedClusters()).thenReturn(List.of(
                new KubeBlocksClient.ManagedClusterSummary("dbaas-orders",
                        "db-known0001", "db-known0001", "orders",
                        "POSTGRESQL", "Running", "known"),
                new KubeBlocksClient.ManagedClusterSummary("dbaas-orders",
                        "db-orphan0001", "db-orphan0001", "orders",
                        "MYSQL", "Running", "orphan")));

        var orphans = service.orphans();

        assertEquals(1, orphans.size());
        assertEquals("db-orphan0001", orphans.get(0).databaseId());
        assertEquals(DatabaseStatus.ORPHANED, orphans.get(0).status());
    }
}
