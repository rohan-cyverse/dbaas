package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.config.DatabaseProperties;
import com.cyfuture.dbaas.dto.BackupSettingsRequest;
import com.cyfuture.dbaas.exception.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BackupConfigurationNormalizerTest {
    private final BackupConfigurationNormalizer normalizer =
            new BackupConfigurationNormalizer(new DatabaseProperties());

    @Test
    void convertsFixedOffsetSchedulesToUtcBeforeKubeBlocksSeesThem() {
        var normalized = normalizer.normalize(new BackupSettingsRequest(
                true, 7, "0 2 * * *", "Asia/Kolkata", false));

        assertEquals("30 20 * * *", normalized.cronExpression());
        assertEquals("Asia/Kolkata", normalized.timezone());
        assertEquals("7d", normalizer.duration(normalized.retentionDays()));
        assertFalse(normalized.pitrEnabled());
    }

    @Test
    void requiresScheduledFullBackupsForPitr() {
        ApiException exception = assertThrows(ApiException.class, () -> normalizer.normalize(
                new BackupSettingsRequest(false, 7, null, "UTC", true)));

        assertEquals("PITR_SCHEDULE_REQUIRED", exception.getCode());
    }

    @Test
    void rejectsDstCronConversionRatherThanSchedulingAtWrongHour() {
        ApiException exception = assertThrows(ApiException.class, () -> normalizer.normalize(
                new BackupSettingsRequest(true, 7, "0 2 * * *", "America/New_York", false)));

        assertEquals("TIMEZONE_SCHEDULE_CONVERSION_UNSUPPORTED", exception.getCode());
    }

    @Test
    void repositoryIsPlatformConfigurationRatherThanAClientInput() {
        assertEquals("cyfuture-dbaas-backuprepo", normalizer.repositoryName());
    }
}
