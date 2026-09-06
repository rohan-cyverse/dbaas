package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.entity.OperationMetadata;
import com.cyfuture.dbaas.model.OperationStatus;
import com.cyfuture.dbaas.model.OperationType;
import com.cyfuture.dbaas.model.ProvisioningStage;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import com.cyfuture.dbaas.repository.OperationMetadataRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KubeBlocksOperationSubmitterTest {
    @Test
    void restartTargetsEveryDatabaseComponent() {
        OperationMetadataRepository operationRepository = mock(OperationMetadataRepository.class);
        DatabaseMetadataRepository databaseRepository = mock(DatabaseMetadataRepository.class);
        KubeBlocksClient kubeBlocksClient = mock(KubeBlocksClient.class);
        KubeBlocksOperationSubmitter submitter = new KubeBlocksOperationSubmitter(
                kubeBlocksClient, operationRepository, databaseRepository);
        OperationMetadata operation = OperationMetadata.builder()
                .operationId("op-restart0001")
                .opsRequestName("op-restart0001")
                .databaseId("db-orders0001")
                .projectName("orders")
                .type(OperationType.RESTART)
                .status(OperationStatus.PENDING)
                .provisioningStage(ProvisioningStage.QUEUED)
                .progress(0)
                .createdAt(Instant.now())
                .build();
        DatabaseMetadata database = new DatabaseMetadata();
        database.setDatabaseId("db-orders0001");
        database.setProjectName("orders");
        database.setNamespaceName("dbaas-orders");
        when(operationRepository.findById(operation.getOperationId())).thenReturn(Optional.of(operation));
        when(databaseRepository.findByDatabaseIdAndProjectName("db-orders0001", "orders"))
                .thenReturn(Optional.of(database));
        when(kubeBlocksClient.componentNames("dbaas-orders", "db-orders0001"))
                .thenReturn(List.of("postgresql", "metrics"));

        submitter.submit(operation.getOperationId());

        verify(kubeBlocksClient).createRestartOpsRequest("dbaas-orders", "db-orders0001",
                "op-restart0001", List.of("postgresql", "metrics"));
    }
}
