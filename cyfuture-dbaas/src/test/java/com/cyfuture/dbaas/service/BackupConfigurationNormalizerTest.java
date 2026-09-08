package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.config.DatabaseProperties;
import com.cyfuture.dbaas.dto.BackupConfigurationRequest;
import com.cyfuture.dbaas.exception.ApiException;
import com.cyfuture.dbaas.model.BackupRetentionPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BackupConfigurationNormalizerTest {
    private final BackupConfigurationNormalizer normalizer =
            new BackupConfigurationNormalizer(new DatabaseProperties());

    @Test
    void convertsFixedOffsetSchedulesToUtcBeforeKubeBlocksSeesThem() {
        var normalized = normalizer.normalize(new BackupConfigurationRequest(
                "cyfuture-dbaas-backuprepo", true, 7, "0 2 * * *", "Asia/Kolkata",
                BackupRetentionPolicy.RETAIN_LATEST, false));

        assertEquals("30 20 * * *", normalized.cronExpression());
        assertEquals("Asia/Kolkata", normalized.timezone());
        assertEquals("7d", normalizer.duration(normalized.retentionDays()));
        assertFalse(normalized.pitrEnabled());
    }

    @Test
    void rejectsPitrWithStableFeatureCode() {
        ApiException exception = assertThrows(ApiException.class, () -> normalizer.normalize(
                new BackupConfigurationRequest("cyfuture-dbaas-backuprepo", true, 7,
                        "0 2 * * *", "UTC", BackupRetentionPolicy.RETAIN_ALL, true)));

        assertEquals("FEATURE_NOT_AVAILABLE", exception.getCode());
    }

    @Test
    void rejectsDstCronConversionRatherThanSchedulingAtWrongHour() {
        ApiException exception = assertThrows(ApiException.class, () -> normalizer.normalize(
                new BackupConfigurationRequest("cyfuture-dbaas-backuprepo", true, 7,
                        "0 2 * * *", "America/New_York", BackupRetentionPolicy.RETAIN_ALL, false)));

        assertEquals("TIMEZONE_SCHEDULE_CONVERSION_UNSUPPORTED", exception.getCode());
    }

    @Test
    void rejectsAnyRepositoryOtherThanPlatformApprovedRepository() {
        ApiException exception = assertThrows(ApiException.class, () -> normalizer.normalize(
                new BackupConfigurationRequest("another-repo", false, 7,
                        null, "UTC", BackupRetentionPolicy.RETAIN_ALL, false)));

        assertEquals("BACKUP_REPOSITORY_NOT_ALLOWED", exception.getCode());
    }
}
