package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.entity.OperationMetadata;
import com.cyfuture.dbaas.model.DatabaseStatus;
import com.cyfuture.dbaas.model.OperationStatus;
import com.cyfuture.dbaas.model.OperationType;
import com.cyfuture.dbaas.model.ProvisioningStage;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import com.cyfuture.dbaas.repository.OperationMetadataRepository;
import com.cyfuture.dbaas.repository.BackupMetadataRepository;
import com.cyfuture.dbaas.repository.RestoreRequestMetadataRepository;
import com.cyfuture.dbaas.repository.BackupPolicyMetadataRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OperationRecoveryServiceTest {
    @Test
    void reconcilesExistingRestartOperationOnStartupWithoutResubmitting() {
        Fixture fixture = new Fixture();

        OperationMetadata operation = OperationMetadata.builder()
                .operationId("op-restart0001")
                .databaseId("db-orders0001")
                .projectName("orders")
                .type(OperationType.RESTART)
                .opsRequestName("op-restart0001")
                .status(OperationStatus.RUNNING)
                .provisioningStage(ProvisioningStage.WAITING_FOR_REPLICAS)
                .progress(50)
                .startedAt(Instant.now())
                .createdAt(Instant.now())
                .build();
        DatabaseMetadata database = database();
        when(fixture.operationRepository.findByStatusIn(List.of(OperationStatus.PENDING, OperationStatus.RUNNING,
                OperationStatus.CANCEL_REQUESTED, OperationStatus.CANCELLING)))
                .thenReturn(List.of(operation));
        when(fixture.databaseRepository.findByDatabaseIdAndProjectName("db-orders0001", "orders"))
                .thenReturn(Optional.of(database));
        when(fixture.kubeBlocksClient.getOpsRequest("dbaas-orders", "op-restart0001"))
                .thenReturn(new KubeBlocksClient.OpsRequestInfo("Running", "1/2",
                        "restart running", null, Instant.now(), null));

        fixture.service.resumeInterruptedOperations();

        verify(fixture.operationRepository).save(operation);
        verify(fixture.operationReconciler).reconcile(operation);
        verify(fixture.submitter, never()).submit("op-restart0001");
    }

    @Test
    void resubmitsKubeBlocksOperationWhenOpsRequestWasNeverCreated() {
        Fixture fixture = new Fixture();

        OperationMetadata operation = OperationMetadata.builder()
                .operationId("op-scale0001")
                .databaseId("db-orders0001")
                .projectName("orders")
                .type(OperationType.HORIZONTAL_SCALING)
                .opsRequestName("op-scale0001")
                .status(OperationStatus.PENDING)
                .provisioningStage(ProvisioningStage.QUEUED)
                .progress(0)
                .createdAt(Instant.now())
                .build();
        when(fixture.operationRepository.findByStatusIn(List.of(OperationStatus.PENDING, OperationStatus.RUNNING,
                OperationStatus.CANCEL_REQUESTED, OperationStatus.CANCELLING)))
                .thenReturn(List.of(operation));
        when(fixture.databaseRepository.findByDatabaseIdAndProjectName("db-orders0001", "orders"))
                .thenReturn(Optional.of(database()));
        when(fixture.kubeBlocksClient.getOpsRequest("dbaas-orders", "op-scale0001"))
                .thenReturn(new KubeBlocksClient.OpsRequestInfo("Pending", "-/-",
                        "Waiting for KubeBlocks OpsRequest submission", null, null, null));

        fixture.service.resumeInterruptedOperations();

        verify(fixture.operationRepository).save(operation);
        verify(fixture.submitter).submit("op-scale0001");
        verify(fixture.operationReconciler, never()).reconcile(operation);
    }

    @Test
    void resumesCredentialRotationThroughCredentialLifecycleServiceOnly() {
        Fixture fixture = new Fixture();

        OperationMetadata operation = OperationMetadata.builder()
                .operationId("op-cred0001")
                .databaseId("db-orders0001")
                .projectName("orders")
                .type(OperationType.ROTATE_CREDENTIALS)
                .status(OperationStatus.RUNNING)
                .provisioningStage(ProvisioningStage.WAITING_FOR_REPLICAS)
                .progress(50)
                .createdAt(Instant.now())
                .build();
        DatabaseMetadata database = database();
        when(fixture.operationRepository.findByStatusIn(List.of(OperationStatus.PENDING, OperationStatus.RUNNING,
                OperationStatus.CANCEL_REQUESTED, OperationStatus.CANCELLING)))
                .thenReturn(List.of(operation));
        when(fixture.databaseRepository.findByDatabaseIdAndProjectName("db-orders0001", "orders"))
                .thenReturn(Optional.of(database));

        fixture.service.resumeInterruptedOperations();

        verify(fixture.credentialLifecycleService).reconcile(database);
        verify(fixture.submitter, never()).submit("op-cred0001");
        verify(fixture.operationReconciler, never()).reconcile(operation);
    }

    private DatabaseMetadata database() {
        DatabaseMetadata database = new DatabaseMetadata();
        database.setDatabaseId("db-orders0001");
        database.setProjectName("orders");
        database.setNamespaceName("dbaas-orders");
        database.setStatus(DatabaseStatus.RUNNING);
        return database;
    }

    private static final class Fixture {
        final OperationMetadataRepository operationRepository = mock(OperationMetadataRepository.class);
        final DatabaseMetadataRepository databaseRepository = mock(DatabaseMetadataRepository.class);
        final AsyncProvisioningService provisioningService = mock(AsyncProvisioningService.class);
        final KubeBlocksOperationSubmitter submitter = mock(KubeBlocksOperationSubmitter.class);
        final KubeBlocksOperationReconciler operationReconciler = mock(KubeBlocksOperationReconciler.class);
        final KubeBlocksClient kubeBlocksClient = mock(KubeBlocksClient.class);
        final CredentialLifecycleService credentialLifecycleService = mock(CredentialLifecycleService.class);
        final OperationRecoveryService service = new OperationRecoveryService(operationRepository,
                databaseRepository, provisioningService, submitter, operationReconciler,
                kubeBlocksClient, credentialLifecycleService,
                mock(BackupMetadataRepository.class), mock(RestoreRequestMetadataRepository.class),
                mock(BackupSubmissionService.class), mock(RestoreSubmissionService.class),
                mock(BackupPolicyMetadataRepository.class),
                mock(BackupPolicySubmissionService.class));
    }
}
