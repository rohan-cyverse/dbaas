package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.entity.OperationMetadata;
import com.cyfuture.dbaas.model.DatabaseStatus;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OperationRecoveryServiceTest {
    @Test
    void resubmitsInterruptedRestartOperationOnStartup() {
        OperationMetadataRepository operationRepository = mock(OperationMetadataRepository.class);
        DatabaseMetadataRepository databaseRepository = mock(DatabaseMetadataRepository.class);
        AsyncProvisioningService provisioningService = mock(AsyncProvisioningService.class);
        KubeBlocksOperationSubmitter submitter = mock(KubeBlocksOperationSubmitter.class);
        OperationRecoveryService service = new OperationRecoveryService(operationRepository,
                databaseRepository, provisioningService, submitter);

        OperationMetadata operation = OperationMetadata.builder()
                .operationId("op-restart0001")
                .databaseId("db-orders0001")
                .projectName("orders")
                .type(OperationType.RESTART)
                .status(OperationStatus.RUNNING)
                .provisioningStage(ProvisioningStage.WAITING_FOR_REPLICAS)
                .progress(50)
                .createdAt(Instant.now())
                .build();
        DatabaseMetadata database = new DatabaseMetadata();
        database.setDatabaseId("db-orders0001");
        database.setProjectName("orders");
        database.setStatus(DatabaseStatus.RUNNING);
        when(operationRepository.findByStatusIn(List.of(OperationStatus.PENDING, OperationStatus.RUNNING)))
                .thenReturn(List.of(operation));
        when(databaseRepository.findByDatabaseIdAndProjectName("db-orders0001", "orders"))
                .thenReturn(Optional.of(database));

        service.resumeInterruptedOperations();

        verify(operationRepository).save(operation);
        verify(submitter).submit("op-restart0001");
    }

    @Test
    void doesNotSubmitInterruptedDeleteOperationAsOpsRequestOnStartup() {
        OperationMetadataRepository operationRepository = mock(OperationMetadataRepository.class);
        DatabaseMetadataRepository databaseRepository = mock(DatabaseMetadataRepository.class);
        AsyncProvisioningService provisioningService = mock(AsyncProvisioningService.class);
        KubeBlocksOperationSubmitter submitter = mock(KubeBlocksOperationSubmitter.class);
        OperationRecoveryService service = new OperationRecoveryService(operationRepository,
                databaseRepository, provisioningService, submitter);

        OperationMetadata operation = OperationMetadata.builder()
                .operationId("op-delete0001")
                .databaseId("db-orders0001")
                .projectName("orders")
                .type(OperationType.DELETE)
                .status(OperationStatus.RUNNING)
                .provisioningStage(ProvisioningStage.WAITING_FOR_REPLICAS)
                .progress(50)
                .createdAt(Instant.now())
                .build();
        DatabaseMetadata database = new DatabaseMetadata();
        database.setDatabaseId("db-orders0001");
        database.setProjectName("orders");
        database.setStatus(DatabaseStatus.DELETING);
        when(operationRepository.findByStatusIn(List.of(OperationStatus.PENDING, OperationStatus.RUNNING)))
                .thenReturn(List.of(operation));
        when(databaseRepository.findByDatabaseIdAndProjectName("db-orders0001", "orders"))
                .thenReturn(Optional.of(database));

        service.resumeInterruptedOperations();

        verify(operationRepository).save(operation);
        verify(submitter, never()).submit("op-delete0001");
    }
}
