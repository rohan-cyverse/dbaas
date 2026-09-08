package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.config.DatabaseProperties;
import com.cyfuture.dbaas.entity.BackupMetadata;
import com.cyfuture.dbaas.model.BackupDeletionMode;
import com.cyfuture.dbaas.model.BackupRetentionPolicy;
import com.cyfuture.dbaas.model.BackupStatus;
import com.cyfuture.dbaas.repository.BackupMetadataRepository;
import com.cyfuture.dbaas.repository.BackupPolicyMetadataRepository;
import com.cyfuture.dbaas.repository.OperationMetadataRepository;
import com.cyfuture.dbaas.repository.RestoreRequestMetadataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackupRetentionServiceTest {
    private BackupMetadataRepository backups;
    private BackupPurgeSubmitter purgeSubmitter;
    private BackupRetentionService service;

    @BeforeEach
    void setUp() {
        backups = mock(BackupMetadataRepository.class);
        purgeSubmitter = mock(BackupPurgeSubmitter.class);
        service = new BackupRetentionService(backups, mock(BackupPolicyMetadataRepository.class),
                mock(RestoreRequestMetadataRepository.class), mock(OperationMetadataRepository.class),
                purgeSubmitter, new BackupConfigurationNormalizer(new DatabaseProperties()));
    }

    @Test
    void retainLatestQueuesOnlyOlderSuccessfulBackupAfterReplacementCompletes() {
        BackupMetadata replacement = completed("bkp-new000001", Instant.parse("2026-09-08T10:00:00Z"),
                BackupRetentionPolicy.RETAIN_LATEST);
        BackupMetadata previous = completed("bkp-old000001", Instant.parse("2026-09-07T10:00:00Z"),
                BackupRetentionPolicy.RETAIN_LATEST);
        when(backups.findByBackupIdForUpdate(replacement.getBackupId())).thenReturn(Optional.of(replacement));
        when(backups.findByProjectNameAndDatabaseIdAndStatusOrderByCompletedAtDesc(
                "prj-orders", "db-orders", BackupStatus.COMPLETED)).thenReturn(List.of(replacement, previous));

        service.recordCompletion(replacement.getBackupId());

        assertEquals(BackupStatus.COMPLETED, replacement.getStatus());
        assertEquals(BackupStatus.DELETING, previous.getStatus());
        assertEquals(BackupDeletionMode.PURGE_DATA, previous.getDeletionMode());
        verify(purgeSubmitter).purge(previous.getBackupId());
    }

    @Test
    void restartScanOfAnOlderCompletionNeverDeletesTheNewestRecoveryPoint() {
        BackupMetadata newest = completed("bkp-new000001", Instant.parse("2026-09-08T10:00:00Z"),
                BackupRetentionPolicy.RETAIN_LATEST);
        BackupMetadata older = completed("bkp-old000001", Instant.parse("2026-09-07T10:00:00Z"),
                BackupRetentionPolicy.RETAIN_LATEST);
        when(backups.findByBackupIdForUpdate(older.getBackupId())).thenReturn(Optional.of(older));
        when(backups.findByProjectNameAndDatabaseIdAndStatusOrderByCompletedAtDesc(
                "prj-orders", "db-orders", BackupStatus.COMPLETED)).thenReturn(List.of(newest, older));

        service.recordCompletion(older.getBackupId());

        assertEquals(BackupStatus.COMPLETED, newest.getStatus());
        assertEquals(BackupStatus.COMPLETED, older.getStatus());
        verify(purgeSubmitter, never()).purge(any());
    }

    @Test
    void failedBackupCannotReplaceOrPurgeTheLatestSuccessfulBackup() {
        BackupMetadata failed = completed("bkp-failed001", Instant.parse("2026-09-08T10:00:00Z"),
                BackupRetentionPolicy.RETAIN_LATEST);
        failed.setStatus(BackupStatus.FAILED);
        when(backups.findByBackupIdForUpdate(failed.getBackupId())).thenReturn(Optional.of(failed));

        service.recordCompletion(failed.getBackupId());

        assertEquals(BackupStatus.FAILED, failed.getStatus());
        verify(purgeSubmitter, never()).purge(any());
        verify(backups, never()).save(failed);
    }

    @Test
    void retainAllSetsAnIndividualExpiryWithoutPurgingTheBackup() {
        Instant completedAt = Instant.parse("2026-09-08T10:00:00Z");
        BackupMetadata backup = completed("bkp-all000001", completedAt, BackupRetentionPolicy.RETAIN_ALL);
        when(backups.findByBackupIdForUpdate(backup.getBackupId())).thenReturn(Optional.of(backup));

        service.recordCompletion(backup.getBackupId());

        assertEquals(completedAt.plus(7, ChronoUnit.DAYS), backup.getExpiresAt());
        verify(purgeSubmitter, never()).purge(any());
    }

    @Test
    void aNewRetainLatestPolicyDoesNotRetroactivelyPurgeAnOlderRetainAllBackup() {
        BackupMetadata replacement = completed("bkp-new000001", Instant.parse("2026-09-08T10:00:00Z"),
                BackupRetentionPolicy.RETAIN_LATEST);
        BackupMetadata historical = completed("bkp-all000001", Instant.parse("2026-09-07T10:00:00Z"),
                BackupRetentionPolicy.RETAIN_ALL);
        when(backups.findByBackupIdForUpdate(replacement.getBackupId())).thenReturn(Optional.of(replacement));
        when(backups.findByProjectNameAndDatabaseIdAndStatusOrderByCompletedAtDesc(
                "prj-orders", "db-orders", BackupStatus.COMPLETED)).thenReturn(List.of(replacement, historical));

        service.recordCompletion(replacement.getBackupId());

        assertEquals(BackupStatus.COMPLETED, historical.getStatus());
        verify(purgeSubmitter, never()).purge(historical.getBackupId());
    }

    private BackupMetadata completed(String id, Instant completedAt, BackupRetentionPolicy retentionPolicy) {
        BackupMetadata backup = new BackupMetadata();
        backup.setBackupId(id);
        backup.setProjectName("prj-orders");
        backup.setDatabaseId("db-orders");
        backup.setStatus(BackupStatus.COMPLETED);
        backup.setRetentionPolicy(retentionPolicy);
        backup.setRetentionPeriod("7d");
        backup.setCompletedAt(completedAt);
        return backup;
    }
}
