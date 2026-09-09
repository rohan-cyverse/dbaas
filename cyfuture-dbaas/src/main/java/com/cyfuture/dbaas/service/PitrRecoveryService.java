package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.config.DatabaseProperties;
import com.cyfuture.dbaas.dto.RecoveryWindowResponse;
import com.cyfuture.dbaas.entity.BackupMetadata;
import com.cyfuture.dbaas.entity.BackupPolicyMetadata;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.exception.ApiException;
import com.cyfuture.dbaas.model.BackupPolicyStatus;
import com.cyfuture.dbaas.model.BackupStatus;
import com.cyfuture.dbaas.model.BackupType;
import com.cyfuture.dbaas.model.PitrStatus;
import com.cyfuture.dbaas.repository.BackupMetadataRepository;
import com.cyfuture.dbaas.repository.BackupPolicyMetadataRepository;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Computes a conservative PITR window from live-imported KubeBlocks backup history. */
@Service
@RequiredArgsConstructor
public class PitrRecoveryService {
    private final BackupPolicyMetadataRepository policyRepository;
    private final BackupMetadataRepository backupRepository;
    private final DatabaseMetadataRepository databaseRepository;
    private final ProjectService projectService;
    private final DatabaseProperties properties;
    private final BackupEngineStrategies strategies;

    @Transactional
    public PitrWindow refresh(BackupPolicyMetadata settings) {
        return calculate(settings, Instant.now());
    }

    @Transactional
    public PitrWindow window(String project, String databaseId) {
        projectService.requireActiveProject(project);
        BackupPolicyMetadata settings = policyRepository.findByProjectNameAndDatabaseId(project, databaseId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "BACKUP_SETTINGS_NOT_CONFIGURED", false,
                        "Backup settings have not been configured for this database."));
        return calculate(settings, Instant.now());
    }

    @Transactional
    public PitrWindow requireRestoreWindow(String project, String databaseId) {
        PitrWindow window = window(project, databaseId);
        if (!window.enabled()) {
            throw new ApiException(HttpStatus.CONFLICT, "PITR_NOT_ENABLED", false,
                    "Point-in-time recovery is not enabled for this database.");
        }
        if (window.status() == PitrStatus.UNHEALTHY) {
            throw new ApiException(HttpStatus.CONFLICT, "CONTINUOUS_BACKUP_UNHEALTHY", false,
                    "Continuous backup coverage is not healthy for point-in-time recovery.");
        }
        if (window.status() != PitrStatus.READY || window.baseBackup() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "PITR_WINDOW_UNAVAILABLE", true,
                    "A recoverable point-in-time window is not available yet.");
        }
        return window;
    }

    public RecoveryWindowResponse response(PitrWindow window) {
        String errorCode = window.status() == PitrStatus.UNHEALTHY ? "PITR_UNHEALTHY" : null;
        String errorMessage = window.status() == PitrStatus.UNHEALTHY ? window.message() : null;
        return new RecoveryWindowResponse(window.databaseId(), window.enabled(), window.status(),
                window.startsAt(), window.endsAt(), errorCode, errorMessage, window.observedAt());
    }

    private PitrWindow calculate(BackupPolicyMetadata settings, Instant now) {
        if (!settings.isPitrEnabled()) {
            return result(settings, PitrStatus.DISABLED, null, null, null, null,
                    "Point-in-time recovery is disabled.", now);
        }
        if (settings.getPolicyStatus() != BackupPolicyStatus.ACTIVE || !settings.isConfigurationApplied()
                || blank(settings.getKubernetesPolicyName())) {
            return result(settings, PitrStatus.PENDING, null, null, null, null,
                    "Waiting for scheduled backup settings to become ready.", now);
        }
        DatabaseMetadata source = databaseRepository.findByDatabaseIdAndProjectName(
                settings.getDatabaseId(), settings.getProjectName()).orElse(null);
        if (source == null) {
            return result(settings, PitrStatus.UNHEALTHY, null, null, null, null,
                    "Source database metadata is unavailable.", now);
        }
        BackupEngineStrategy strategy = strategies.require(source.getEngine());
        String fullMethod = strategy.manualFullMethod();
        String continuousMethod = strategy.continuousMethod();
        if (blank(continuousMethod)) {
            return result(settings, PitrStatus.UNHEALTHY, null, null, null, null,
                    "This database engine does not expose continuous backup coverage.", now);
        }

        List<BackupMetadata> all = backupRepository.findByProjectNameAndDatabaseIdOrderByCreatedAtDesc(
                settings.getProjectName(), settings.getDatabaseId());
        List<BackupMetadata> bases = all.stream()
                .filter(backup -> backup.getBackupType() == BackupType.FULL)
                .filter(backup -> backup.getStatus() == BackupStatus.COMPLETED)
                .filter(backup -> fullMethod.equals(backup.getBackupMethod()))
                .filter(backup -> backup.getCompletedAt() != null)
                .sorted(Comparator.comparing(BackupMetadata::getCompletedAt).reversed())
                .toList();
        if (bases.isEmpty()) {
            return result(settings, PitrStatus.PENDING, null, null, null, null,
                    "Waiting for a completed scheduled full backup.", now);
        }
        List<BackupMetadata> continuous = all.stream()
                .filter(backup -> backup.getBackupType() == BackupType.CONTINUOUS)
                .filter(backup -> continuousMethod.equals(backup.getBackupMethod()))
                .toList();
        if (continuous.isEmpty()) {
            return result(settings, PitrStatus.PENDING, null, null, bases.get(0), null,
                    "Waiting for continuous log backup coverage.", now);
        }

        Map<String, BackupMetadata> byId = new HashMap<>();
        Map<String, BackupMetadata> byKubernetesName = new HashMap<>();
        for (BackupMetadata backup : all) {
            byId.put(backup.getBackupId(), backup);
            if (!blank(backup.getKubernetesBackupName())) byKubernetesName.put(backup.getKubernetesBackupName(), backup);
        }
        ChainResult fallback = null;
        for (BackupMetadata base : bases) {
            ChainResult chain = evaluateChain(base, continuous, byId, byKubernetesName, now);
            if (chain.status() == PitrStatus.READY) {
                return result(settings, chain.status(), chain.from(), chain.until(), base,
                        chain.latestContinuous(), chain.message(), now);
            }
            if (fallback == null || rank(chain.status()) > rank(fallback.status())) fallback = chain;
        }
        ChainResult result = fallback == null
                ? new ChainResult(PitrStatus.PENDING, null, null, null,
                "Waiting for continuous log backup coverage.") : fallback;
        return result(settings, result.status(), result.from(), result.until(), bases.get(0),
                result.latestContinuous(), result.message(), now);
    }

    private ChainResult evaluateChain(BackupMetadata base, List<BackupMetadata> continuous,
                                      Map<String, BackupMetadata> byId,
                                      Map<String, BackupMetadata> byKubernetesName, Instant now) {
        List<BackupMetadata> chain = continuous.stream()
                .filter(backup -> belongsToBase(backup, base, byId, byKubernetesName))
                .toList();
        if (chain.isEmpty()) return new ChainResult(PitrStatus.PENDING, null, null, null,
                "Waiting for continuous log backup coverage.");
        if (chain.stream().anyMatch(this::terminalContinuousFailure)) {
            return new ChainResult(PitrStatus.UNHEALTHY, null, null, null,
                    "A continuous log backup is no longer healthy.");
        }
        List<BackupMetadata> segments = chain.stream()
                .filter(backup -> backup.getStatus() == BackupStatus.RUNNING
                        || backup.getStatus() == BackupStatus.COMPLETED)
                .filter(backup -> backup.getCoverageStart() != null && backup.getCoverageEnd() != null)
                .filter(backup -> !backup.getCoverageEnd().isBefore(backup.getCoverageStart()))
                .sorted(Comparator.comparing(BackupMetadata::getCoverageStart)
                        .thenComparing(BackupMetadata::getCoverageEnd))
                .toList();
        if (segments.isEmpty()) return new ChainResult(PitrStatus.PENDING, null, null, null,
                "Continuous backups have not published a recovery range yet.");

        Instant from = base.getCompletedAt();
        Instant coveredUntil = from;
        BackupMetadata latest = null;
        long maxGapMs = Math.max(0L, properties.getBackup().getContinuousMaxGapMs());
        for (BackupMetadata segment : segments) {
            if (segment.getCoverageStart().isAfter(coveredUntil.plusMillis(maxGapMs))) {
                return new ChainResult(PitrStatus.UNHEALTHY, from, coveredUntil, latest,
                        "Continuous log coverage contains a gap.");
            }
            if (segment.getCoverageEnd().isAfter(coveredUntil)) {
                coveredUntil = segment.getCoverageEnd();
                latest = segment;
            }
        }
        if (latest == null || !coveredUntil.isAfter(from)) return new ChainResult(PitrStatus.PENDING,
                null, null, null, "Continuous log coverage is not ready yet.");
        if (coveredUntil.isAfter(now) || !coveredUntil.plusMillis(
                Math.max(1L, properties.getBackup().getContinuousStaleMs())).isAfter(now)) {
            return new ChainResult(PitrStatus.UNHEALTHY, from, coveredUntil, latest,
                    "Continuous log coverage is stale or invalid.");
        }
        return new ChainResult(PitrStatus.READY, from, coveredUntil, latest,
                "Point-in-time recovery is available.");
    }

    private boolean belongsToBase(BackupMetadata candidate, BackupMetadata base,
                                  Map<String, BackupMetadata> byId,
                                  Map<String, BackupMetadata> byKubernetesName) {
        if (base.getBackupId().equals(candidate.getBaseBackupId())
                || base.getKubernetesBackupName().equals(candidate.getBaseKubernetesBackupName())) return true;
        BackupMetadata cursor = candidate;
        Set<String> visited = new HashSet<>();
        for (int depth = 0; depth < 64 && cursor != null && visited.add(cursor.getBackupId()); depth++) {
            if (base.getBackupId().equals(cursor.getBackupId())) return true;
            BackupMetadata parent = !blank(cursor.getParentBackupId())
                    ? byId.get(cursor.getParentBackupId()) : null;
            if (parent == null && !blank(cursor.getParentKubernetesBackupName())) {
                parent = byKubernetesName.get(cursor.getParentKubernetesBackupName());
            }
            cursor = parent;
        }
        return false;
    }

    private boolean terminalContinuousFailure(BackupMetadata backup) {
        return backup.getStatus() == BackupStatus.FAILED || backup.getStatus() == BackupStatus.DELETED
                || backup.getStatus() == BackupStatus.EXPIRED;
    }

    private int rank(PitrStatus status) {
        return switch (status) {
            case READY -> 4;
            case UNHEALTHY -> 3;
            case PENDING -> 2;
            case DISABLED -> 1;
        };
    }

    private PitrWindow result(BackupPolicyMetadata settings, PitrStatus status, Instant from, Instant until,
                              BackupMetadata base, BackupMetadata latestContinuous, String message,
                              Instant observedAt) {
        return new PitrWindow(settings.getDatabaseId(), settings.isPitrEnabled(), status, from, until,
                base, latestContinuous, message, observedAt);
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }

    public record PitrWindow(String databaseId, boolean enabled, PitrStatus status, Instant startsAt,
                             Instant endsAt, BackupMetadata baseBackup, BackupMetadata latestContinuousBackup,
                             String message, Instant observedAt) {}

    private record ChainResult(PitrStatus status, Instant from, Instant until,
                               BackupMetadata latestContinuous, String message) {}
}
