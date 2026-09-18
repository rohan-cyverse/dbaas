package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.entity.BackupPolicyMetadata;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.model.BackupPolicyStatus;
import com.cyfuture.dbaas.repository.BackupPolicyMetadataRepository;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import com.cyfuture.dbaas.repository.OperationMetadataRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BackupPolicyReconcilerTest {
    @Test
    void creationPolicyWaitsForItsSchedulePatchBeforeItCanBecomeActive() {
        BackupPolicyMetadataRepository policyRepository = mock(BackupPolicyMetadataRepository.class);
        DatabaseMetadataRepository databaseRepository = mock(DatabaseMetadataRepository.class);
        KubeBlocksClient kubeBlocksClient = mock(KubeBlocksClient.class);
        BackupPolicySubmissionService submissionService = mock(BackupPolicySubmissionService.class);
        BackupPolicyReconciler reconciler = new BackupPolicyReconciler(policyRepository, databaseRepository,
                mock(OperationMetadataRepository.class), kubeBlocksClient, submissionService,
                mock(ScheduledBackupDiscoveryService.class), mock(PitrRecoveryService.class),
                mock(BackupEngineStrategies.class), mock(BackupConfigurationNormalizer.class));
        BackupPolicyMetadata policy = new BackupPolicyMetadata();
        policy.setPolicyId("bks-orders0001");
        policy.setProjectName("prj-orders");
        policy.setDatabaseId("db-orders0001");
        policy.setPolicyStatus(BackupPolicyStatus.PENDING);
        policy.setConfigurationApplied(false);
        DatabaseMetadata database = new DatabaseMetadata();
        database.setDatabaseId("db-orders0001");
        database.setProjectName("prj-orders");

        when(databaseRepository.findByDatabaseIdAndProjectName("db-orders0001", "prj-orders"))
                .thenReturn(Optional.of(database));

        reconciler.refresh(policy);

        verify(submissionService).submit("bks-orders0001");
        verifyNoInteractions(kubeBlocksClient);
        verify(policyRepository, never()).save(any());
        assertEquals(BackupPolicyStatus.PENDING, policy.getPolicyStatus());
    }
}
