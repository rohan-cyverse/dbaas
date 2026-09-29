package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.entity.BackupMetadata;
import com.cyfuture.dbaas.entity.BackupPolicyMetadata;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.model.BackupTriggerMethod;
import com.cyfuture.dbaas.model.BackupType;
import com.cyfuture.dbaas.model.DatabaseEngine;
import com.cyfuture.dbaas.repository.BackupMetadataRepository;
import com.cyfuture.dbaas.repository.BackupPolicyMetadataRepository;
import com.cyfuture.dbaas.repository.OperationMetadataRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduledBackupBootstrapServiceTest {
    @Test
    void enablingScheduleQueuesOneFullBackupWithPolicyRetention() {
        BackupPolicyMetadataRepository policyRepository = mock(BackupPolicyMetadataRepository.class);
        BackupMetadataRepository backupRepository = mock(BackupMetadataRepository.class);
        OperationMetadataRepository operationRepository = mock(OperationMetadataRepository.class);
        BackupSubmissionService submissionService = mock(BackupSubmissionService.class);
        BackupConfigurationNormalizer normalizer = mock(BackupConfigurationNormalizer.class);
        ScheduledBackupBootstrapService service = new ScheduledBackupBootstrapService(policyRepository,
                backupRepository, operationRepository, submissionService, normalizer);
        BackupPolicyMetadata policy = policy();
        DatabaseMetadata database = new DatabaseMetadata();
        database.setEngine(DatabaseEngine.POSTGRESQL);
        BackupEngineStrategy strategy = mock(BackupEngineStrategy.class);
        when(strategy.manualFullMethod()).thenReturn("volume-snapshot");
        when(normalizer.duration(14)).thenReturn("14d");
        when(backupRepository.findById(any())).thenReturn(Optional.empty());

        service.ensureInitialFull(policy, database, strategy);

        var captor = org.mockito.ArgumentCaptor.forClass(BackupMetadata.class);
        verify(backupRepository).save(captor.capture());
        BackupMetadata created = captor.getValue();
        assertEquals(BackupType.FULL, created.getBackupType());
        assertEquals(BackupTriggerMethod.SCHEDULED, created.getTriggerMethod());
        assertEquals("14d", created.getRetentionPeriod());
        assertNotNull(policy.getInitialBackupId());
        assertEquals(true, policy.isInitialBackupRequired());
        verify(submissionService).submit(created.getBackupId());
    }

    @Test
    void completedBootstrapStateDoesNotCreateAnotherBackup() {
        BackupMetadataRepository backupRepository = mock(BackupMetadataRepository.class);
        ScheduledBackupBootstrapService service = new ScheduledBackupBootstrapService(
                mock(BackupPolicyMetadataRepository.class), backupRepository,
                mock(OperationMetadataRepository.class), mock(BackupSubmissionService.class),
                mock(BackupConfigurationNormalizer.class));
        BackupPolicyMetadata policy = policy();
        policy.setInitialBackupRequired(false);

        service.ensureInitialFull(policy, new DatabaseMetadata(), mock(BackupEngineStrategy.class));

        verify(backupRepository, never()).save(any());
    }

    @Test
    void completedInitialFullReleasesPitrPolicyApplication() {
        BackupPolicyMetadataRepository policyRepository = mock(BackupPolicyMetadataRepository.class);
        BackupMetadataRepository backupRepository = mock(BackupMetadataRepository.class);
        ScheduledBackupBootstrapService service = new ScheduledBackupBootstrapService(policyRepository,
                backupRepository, mock(OperationMetadataRepository.class),
                mock(BackupSubmissionService.class), mock(BackupConfigurationNormalizer.class));
        BackupPolicyMetadata policy = policy();
        BackupMetadata completed = new BackupMetadata();
        completed.setStatus(com.cyfuture.dbaas.model.BackupStatus.COMPLETED);
        when(backupRepository.findById(any())).thenReturn(Optional.of(completed));

        service.ensureInitialFull(policy, new DatabaseMetadata(), mock(BackupEngineStrategy.class));

        assertNotNull(policy.getInitialBackupId());
        assertFalse(policy.isInitialBackupRequired());
        assertFalse(policy.isConfigurationApplied());
        verify(policyRepository).save(policy);
    }

    private BackupPolicyMetadata policy() {
        BackupPolicyMetadata policy = new BackupPolicyMetadata();
        policy.setPolicyId("bks-orders0001");
        policy.setPolicyUpdateOperationId("op-settings0001");
        policy.setProjectName("orders");
        policy.setDatabaseId("db-orders0001");
        policy.setAutoBackupEnabled(true);
        policy.setInitialBackupRequired(true);
        policy.setRetentionDays(14);
        return policy;
    }
}
