package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.entity.BackupMetadata;
import com.cyfuture.dbaas.model.BackupStatus;
import com.cyfuture.dbaas.model.BackupTriggerMethod;
import com.cyfuture.dbaas.model.BackupType;
import com.cyfuture.dbaas.repository.BackupMetadataRepository;
import com.cyfuture.dbaas.repository.BackupPolicyMetadataRepository;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import com.cyfuture.dbaas.repository.OperationMetadataRepository;
import com.cyfuture.dbaas.repository.RestoreRequestMetadataRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackupServiceTest {

    @Test
    void deleteIsQueuedWithoutConsultingActiveRestoresOrPitr() {
        DatabaseMetadataRepository databaseRepository = mock(DatabaseMetadataRepository.class);
        BackupMetadataRepository backupRepository = mock(BackupMetadataRepository.class);
        BackupPolicyMetadataRepository policyRepository = mock(BackupPolicyMetadataRepository.class);
        RestoreRequestMetadataRepository restoreRepository = mock(RestoreRequestMetadataRepository.class);
        OperationMetadataRepository operationRepository = mock(OperationMetadataRepository.class);
        ProjectService projectService = mock(ProjectService.class);
        BackupDeletionSubmitter deletionSubmitter = mock(BackupDeletionSubmitter.class);
        BackupService service = new BackupService(databaseRepository, backupRepository, policyRepository,
                restoreRepository, operationRepository, projectService, mock(BackupEngineStrategies.class),
                mock(BackupConfigurationNormalizer.class), mock(KubeBlocksClient.class),
                mock(BackupSubmissionService.class), deletionSubmitter, mock(BackupPolicyReconciler.class),
                mock(BackupReconciler.class), mock(OperationService.class));
        BackupMetadata backup = new BackupMetadata();
        backup.setBackupId("bkp-orders0001");
        backup.setOperationId("op-backup0001");
        backup.setProjectName("orders");
        backup.setDatabaseId("db-orders0001");
        backup.setBackupType(BackupType.FULL);
        backup.setTriggerMethod(BackupTriggerMethod.MANUAL);
        backup.setStatus(BackupStatus.COMPLETED);
        backup.setCreatedAt(Instant.now());
        when(backupRepository.findByBackupIdAndProjectNameAndDatabaseId(
                "bkp-orders0001", "orders", "db-orders0001")).thenReturn(Optional.of(backup));

        var response = service.delete("orders", "db-orders0001", "bkp-orders0001");

        assertEquals(BackupStatus.DELETING, response.status());
        verify(backupRepository).save(backup);
        verify(deletionSubmitter).delete("bkp-orders0001");
        verify(restoreRepository, never()).existsByProjectNameAndSourceBackupIdAndStatusIn(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyCollection());
    }
}
