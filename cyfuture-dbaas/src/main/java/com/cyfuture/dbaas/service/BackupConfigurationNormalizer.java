package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.config.DatabaseProperties;
import com.cyfuture.dbaas.dto.BackupConfigurationRequest;
import com.cyfuture.dbaas.exception.ApiException;
import com.cyfuture.dbaas.model.BackupRetentionPolicy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneRules;
import java.util.regex.Pattern;

/**
 * Validates client backup configuration and converts a safe subset of five-field
 * cron expressions to the UTC format persisted in Cluster.spec.backup.
 *
 * KubeBlocks has no timezone field on Cluster.spec.backup. A DST-aware cron
 * cannot be converted once without changing its meaning, so it is rejected
 * rather than being scheduled at a wrong hour.
 */
@Component
public class BackupConfigurationNormalizer {
    public static final int DEFAULT_RETENTION_DAYS = 7;
    public static final int MAX_RETENTION_DAYS = 3650;
    private static final Pattern DURATION_DAYS = Pattern.compile("^([1-9][0-9]*)d$");

    private final DatabaseProperties properties;

    public BackupConfigurationNormalizer(DatabaseProperties properties) {
        this.properties = properties;
    }

    public NormalizedBackupConfiguration normalize(BackupConfigurationRequest request) {
        if (request == null) return defaults();
        String repository = textOr(request.repository(), properties.getBackup().getRepositoryName());
        if (!properties.getBackup().getRepositoryName().equals(repository)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "BACKUP_REPOSITORY_NOT_ALLOWED", false,
                    "Only the platform-approved BackupRepo can be used.");
        }
        if (Boolean.TRUE.equals(request.pitrEnabled())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "FEATURE_NOT_AVAILABLE", false,
                    "Point-in-time recovery is not available yet.");
        }
        int retentionDays = request.retentionDays() == null
                ? defaultRetentionDays() : request.retentionDays();
        if (retentionDays < 1 || retentionDays > MAX_RETENTION_DAYS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BACKUP_RETENTION", false,
                    "retentionDays must be between 1 and " + MAX_RETENTION_DAYS + ".");
        }
        boolean enabled = Boolean.TRUE.equals(request.autoBackupEnabled());
        String timezone = textOr(request.timezone(), "UTC");
        ZoneId zone;
        try {
            zone = ZoneId.of(timezone);
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BACKUP_TIMEZONE", false,
                    "timezone must be a valid IANA timezone or UTC offset.");
        }
        String cron = blank(request.cronExpression()) ? null : request.cronExpression().trim();
        if (enabled && cron == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BACKUP_CRON_REQUIRED", false,
                    "cronExpression is required when automatic backups are enabled.");
        }
        if (cron != null) cron = normalizeCronToUtc(cron, zone);
        return new NormalizedBackupConfiguration(repository, enabled, retentionDays, cron, zone.getId(),
                request.retentionPolicy() == null ? BackupRetentionPolicy.RETAIN_ALL : request.retentionPolicy(),
                false);
    }

    public NormalizedBackupConfiguration defaults() {
        return new NormalizedBackupConfiguration(properties.getBackup().getRepositoryName(), false,
                defaultRetentionDays(), null, "UTC", BackupRetentionPolicy.RETAIN_ALL, false);
    }

    public String duration(int days) {
        if (days < 1 || days > MAX_RETENTION_DAYS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BACKUP_RETENTION", false,
                    "retentionDays must be between 1 and " + MAX_RETENTION_DAYS + ".");
        }
        return days + "d";
    }

    public int retentionDays(String duration) {
        if (duration != null) {
            var matcher = DURATION_DAYS.matcher(duration.trim());
            if (matcher.matches()) {
                try { return Integer.parseInt(matcher.group(1)); } catch (NumberFormatException ignored) { }
            }
        }
        return DEFAULT_RETENTION_DAYS;
    }

    private int defaultRetentionDays() {
        int days = retentionDays(properties.getBackup().getDefaultRetention());
        return days < 1 || days > MAX_RETENTION_DAYS ? DEFAULT_RETENTION_DAYS : days;
    }

    private String normalizeCronToUtc(String expression, ZoneId zone) {
        String[] values = expression.trim().split("\\s+");
        if (values.length != 5 || !validCron(values)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BACKUP_CRON", false,
                    "cronExpression must be a valid five-field cron expression.");
        }
        if (ZoneOffset.UTC.equals(zone.getRules().getOffset(Instant.now()))) {
            // A named UTC-equivalent zone has the same semantics as UTC.
            ZoneRules rules = zone.getRules();
            if (rules.nextTransition(Instant.now()) == null) return String.join(" ", values);
        }
        if ("UTC".equalsIgnoreCase(zone.getId()) || "Z".equalsIgnoreCase(zone.getId())) {
            return String.join(" ", values);
        }
        ZoneRules rules = zone.getRules();
        if (rules.nextTransition(Instant.now()) != null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "TIMEZONE_SCHEDULE_CONVERSION_UNSUPPORTED", false,
                    "Automatic backup schedules in a daylight-saving timezone must be supplied in UTC.");
        }
        // Convert only a fixed local clock time. Ranges, steps and day-of-month
        // selectors become ambiguous after a date shift.
        if (!values[0].matches("[0-9]{1,2}") || !values[1].matches("[0-9]{1,2}")
                || !"*".equals(values[2]) || !"*".equals(values[3])
                || !("*".equals(values[4]) || values[4].matches("[0-7]"))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "TIMEZONE_SCHEDULE_CONVERSION_UNSUPPORTED", false,
                    "Non-UTC schedules must use a fixed minute/hour and daily or weekly selector.");
        }
        int minute = Integer.parseInt(values[0]);
        int hour = Integer.parseInt(values[1]);
        ZoneOffset offset = rules.getOffset(LocalDate.now().atTime(hour, minute).atZone(zone).toInstant());
        int localMinutes = hour * 60 + minute;
        int utcMinutes = Math.floorMod(localMinutes - offset.getTotalSeconds() / 60, 24 * 60);
        int dayShift = Math.floorDiv(localMinutes - offset.getTotalSeconds() / 60, 24 * 60);
        String dayOfWeek = values[4];
        if (!"*".equals(dayOfWeek)) {
            dayOfWeek = String.valueOf(Math.floorMod(Integer.parseInt(dayOfWeek) + dayShift, 7));
        }
        return (utcMinutes % 60) + " " + (utcMinutes / 60) + " * * " + dayOfWeek;
    }

    private boolean validCron(String[] value) {
        return cronField(value[0], 0, 59) && cronField(value[1], 0, 23)
                && cronField(value[2], 1, 31) && cronField(value[3], 1, 12)
                && cronField(value[4], 0, 7);
    }

    private boolean cronField(String field, int min, int max) {
        for (String part : field.split(",")) {
            String[] step = part.split("/", -1);
            if (step.length > 2 || (step.length == 2 && !numberInRange(step[1], 1, max - min + 1))) {
                return false;
            }
            String base = step[0];
            if ("*".equals(base)) continue;
            String[] range = base.split("-", -1);
            if (range.length == 1) {
                if (!numberInRange(base, min, max)) return false;
            } else if (range.length == 2) {
                if (!numberInRange(range[0], min, max) || !numberInRange(range[1], min, max)
                        || Integer.parseInt(range[0]) > Integer.parseInt(range[1])) return false;
            } else return false;
        }
        return true;
    }

    private boolean numberInRange(String value, int min, int max) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= min && parsed <= max;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private String textOr(String value, String fallback) {
        return blank(value) ? fallback : value.trim();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record NormalizedBackupConfiguration(
            String repository,
            boolean autoBackupEnabled,
            int retentionDays,
            String cronExpression,
            String timezone,
            BackupRetentionPolicy retentionPolicy,
            boolean pitrEnabled
    ) {}
}
