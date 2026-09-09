package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.config.DatabaseProperties;
import com.cyfuture.dbaas.dto.RecoveryWindowResponse;
import com.cyfuture.dbaas.entity.BackupMetadata;
import com.cyfuture.dbaas.entity.BackupPolicyMetadata;
import com.cyfuture.dbaas.exception.ApiException;
import com.cyfuture.dbaas.model.BackupPolicyStatus;
import com.cyfuture.dbaas.model.BackupStatus;
import com.cyfuture.dbaas.model.BackupType;
import com.cyfuture.dbaas.model.PitrStatus;
import com.cyfuture.dbaas.repository.BackupMetadataRepository;
import com.cyfuture.dbaas.repository.BackupPolicyMetadataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Computes a conservative PITR window exclusively from persisted KubeBlocks
 * observations. It never treats a desired pitrEnabled flag or a polling time
 * as proof of recoverability.
 */
@Service
@RequiredArgsConstructor
public class PitrRecoveryService {
    private final BackupPolicyMetadataRepository policyRepository;
    private final BackupMetadataRepository backupRepository;
    private final ProjectService projectService;
    private final DatabaseProperties properties;

    @Transactional
    public PitrWindow refresh(BackupPolicyMetadata policy) {
        PitrWindow window = calculate(policy, Instant.now());
        boolean changed = policy.getPitrStatus() != window.status()
                || !Objects.equals(policy.getPitrMessage(), window.message())
                || !Objects.equals(policy.getRecoverableFrom(), window.recoverableFrom())
                || !Objects.equals(policy.getRecoverableUntil(), window.recoverableUntil())
                || policy.getPitrObservedAt() == null
                || policy.getPitrObservedAt().plusSeconds(30).isBefore(window.observedAt());
        if (changed) {
            policy.setPitrStatus(window.status());
            policy.setPitrMessage(window.message());
            policy.setRecoverableFrom(window.recoverableFrom());
            policy.setRecoverableUntil(window.recoverableUntil());
            policy.setPitrObservedAt(window.observedAt());
            policyRepository.save(policy);
        }
        return window;
    }

    @Transactional
    public PitrWindow window(String project, String databaseId) {
        projectService.requireActiveProject(project);
        BackupPolicyMetadata policy = policyRepository.findByProjectNameAndDatabaseId(project, databaseId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "BACKUP_POLICY_NOT_CONFIGURED", false,
                        "No backup policy has been configured for this database."));
        return refresh(policy);
    }

    @Transactional
    public PitrWindow requireRestoreWindow(String project, String databaseId) {
        projectService.requireActiveProject(project);
        BackupPolicyMetadata policy = policyRepository.findByProjectNameAndDatabaseId(project, databaseId)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "PITR_NOT_ENABLED", false,
                        "Point-in-time recovery is not enabled for this database."));
        PitrWindow window = refresh(policy);
        if (!window.pitrEnabled()) {
            throw new ApiException(HttpStatus.CONFLICT, "PITR_NOT_ENABLED", false,
                    "Point-in-time recovery is not enabled for this database.");
        }
        if (policy.getPolicyStatus() != BackupPolicyStatus.ACTIVE || !policy.isConfigurationApplied()) {
            throw new ApiException(HttpStatus.CONFLICT, "BACKUP_POLICY_NOT_READY", true,
                    "The KubeBlocks backup policy is not ready for point-in-time recovery.");
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
        return new RecoveryWindowResponse(window.project(), window.databaseId(), window.pitrEnabled(),
                window.status(), window.continuousMethod(), window.recoverableFrom(),
                window.recoverableUntil(), window.message(), window.observedAt());
    }

    private PitrWindow calculate(BackupPolicyMetadata policy, Instant now) {
        if (!policy.isPitrEnabled()) {
            return result(policy, PitrStatus.DISABLED, null, null, null, null,
                    "Point-in-time recovery is disabled.", now);
        }
        if (policy.getPolicyStatus() != BackupPolicyStatus.ACTIVE || !policy.isConfigurationApplied()
                || blank(policy.getKubernetesPolicyName())) {
            return result(policy, PitrStatus.PENDING, null, null, null, null,
                    "Waiting for the KubeBlocks backup policy and schedule to become available.", now);
        }
        if (blank(policy.getDefaultBackupMethod()) || blank(policy.getContinuousBackupMethod())) {
            return result(policy, PitrStatus.UNHEALTHY, null, null, null, null,
                    "The observed backup policy does not expose both full and continuous methods.", now);
        }

        List<BackupMetadata> all = backupRepository.findByProjectNameAndDatabaseIdOrderByCreatedAtDesc(
                policy.getProjectName(), policy.getDatabaseId());
        List<BackupMetadata> bases = all.stream()
                .filter(backup -> backup.getBackupType() == BackupType.FULL)
                .filter(backup -> backup.getStatus() == BackupStatus.COMPLETED)
                .filter(backup -> policy.getDefaultBackupMethod().equals(backup.getBackupMethod()))
                .filter(backup -> backup.getCompletedAt() != null)
                .sorted(Comparator.comparing(BackupMetadata::getCompletedAt).reversed())
                .toList();
        if (bases.isEmpty()) {
            return result(policy, PitrStatus.PENDING, null, null, null, null,
                    "Waiting for a completed scheduled full backup.", now);
        }

        List<BackupMetadata> continuous = all.stream()
                .filter(backup -> backup.getBackupType() == BackupType.CONTINUOUS)
                .filter(backup -> policy.getContinuousBackupMethod().equals(backup.getBackupMethod()))
                .toList();
        if (continuous.isEmpty()) {
            return result(policy, PitrStatus.PENDING, null, null, bases.get(0), null,
                    "Waiting for KubeBlocks continuous log backup coverage.", now);
        }

        Map<String, BackupMetadata> byId = new HashMap<>();
        Map<String, BackupMetadata> byKubernetesName = new HashMap<>();
        for (BackupMetadata backup : all) {
            byId.put(backup.getBackupId(), backup);
            if (!blank(backup.getKubernetesBackupName())) {
                byKubernetesName.put(backup.getKubernetesBackupName(), backup);
            }
        }

        ChainResult fallback = null;
        for (BackupMetadata base : bases) {
            ChainResult chain = evaluateChain(base, continuous, byId, byKubernetesName, now);
            if (chain.status() == PitrStatus.READY) {
                return result(policy, chain.status(), chain.from(), chain.until(), base,
                        chain.latestContinuous(), chain.message(), now);
            }
            if (fallback == null || rank(chain.status()) > rank(fallback.status())) fallback = chain;
        }
        BackupMetadata latestBase = bases.get(0);
        if (fallback == null) {
            return result(policy, PitrStatus.PENDING, null, null, latestBase, null,
                    "Waiting for continuous log backup coverage for the completed base backup.", now);
        }
        return result(policy, fallback.status(), fallback.from(), fallback.until(), latestBase,
                fallback.latestContinuous(), fallback.message(), now);
    }

    private ChainResult evaluateChain(BackupMetadata base, List<BackupMetadata> continuous,
                                      Map<String, BackupMetadata> byId,
                                      Map<String, BackupMetadata> byKubernetesName, Instant now) {
        List<BackupMetadata> chain = continuous.stream()
                .filter(backup -> belongsToBase(backup, base, byId, byKubernetesName))
                .toList();
        if (chain.isEmpty()) {
            return new ChainResult(PitrStatus.PENDING, null, null, null,
                    "Waiting for continuous log backup coverage for the completed base backup.");
        }
        if (chain.stream().anyMatch(this::terminalContinuousFailure)) {
            return new ChainResult(PitrStatus.UNHEALTHY, null, null, null,
                    "A continuous log backup in the recovery chain is no longer healthy.");
        }

        List<BackupMetadata> segments = chain.stream()
                .filter(backup -> backup.getStatus() == BackupStatus.RUNNING
                        || backup.getStatus() == BackupStatus.COMPLETED)
                .filter(backup -> backup.getCoverageStart() != null && backup.getCoverageEnd() != null)
                .filter(backup -> !backup.getCoverageEnd().isBefore(backup.getCoverageStart()))
                .sorted(Comparator.comparing(BackupMetadata::getCoverageStart)
                        .thenComparing(BackupMetadata::getCoverageEnd))
                .toList();
        if (segments.isEmpty()) {
            return new ChainResult(PitrStatus.PENDING, null, null, null,
                    "Continuous backups have not published an actual KubeBlocks time range yet.");
        }

        Instant from = base.getCompletedAt();
        Instant coveredUntil = from;
        BackupMetadata latest = null;
        long maxGapMs = Math.max(0L, properties.getBackup().getContinuousMaxGapMs());
        for (BackupMetadata segment : segments) {
            Instant start = segment.getCoverageStart();
            Instant end = segment.getCoverageEnd();
            if (start.isAfter(coveredUntil.plusMillis(maxGapMs))) {
                return new ChainResult(PitrStatus.UNHEALTHY, from, coveredUntil, latest,
                        "KubeBlocks reported a gap in continuous log coverage.");
            }
            if (end.isAfter(coveredUntil)) {
                coveredUntil = end;
                latest = segment;
            }
        }
        if (latest == null || !coveredUntil.isAfter(from)) {
            return new ChainResult(PitrStatus.PENDING, null, null, null,
                    "Continuous log coverage does not yet extend beyond the completed base backup.");
        }
        if (coveredUntil.isAfter(now)) {
            return new ChainResult(PitrStatus.UNHEALTHY, from, coveredUntil, latest,
                    "KubeBlocks reported an invalid future continuous log coverage timestamp.");
        }
        if (!coveredUntil.plusMillis(Math.max(1L, properties.getBackup().getContinuousStaleMs())).isAfter(now)) {
            return new ChainResult(PitrStatus.UNHEALTHY, from, coveredUntil, latest,
                    "Continuous log coverage is stale.");
        }
        return new ChainResult(PitrStatus.READY, from, coveredUntil, latest,
                "A completed base backup and continuous log coverage are ready for PITR.");
    }

    private boolean belongsToBase(BackupMetadata candidate, BackupMetadata base,
                                  Map<String, BackupMetadata> byId,
                                  Map<String, BackupMetadata> byKubernetesName) {
        if (base.getBackupId().equals(candidate.getBaseBackupId())
                || (!blank(candidate.getBaseKubernetesBackupName())
                && candidate.getBaseKubernetesBackupName().equals(base.getKubernetesBackupName()))) {
            return true;
        }
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
            case UNHEALTHY -> 3;
            case PENDING -> 2;
            case DISABLED -> 1;
            case READY -> 4;
        };
    }

    private PitrWindow result(BackupPolicyMetadata policy, PitrStatus status, Instant from, Instant until,
                              BackupMetadata base, BackupMetadata latestContinuous, String message,
                              Instant observedAt) {
        return new PitrWindow(policy.getProjectName(), policy.getDatabaseId(), policy.isPitrEnabled(), status,
                policy.getContinuousBackupMethod(), from, until, base, latestContinuous, message, observedAt);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record PitrWindow(
            String project,
            String databaseId,
            boolean pitrEnabled,
            PitrStatus status,
            String continuousMethod,
            Instant recoverableFrom,
            Instant recoverableUntil,
            BackupMetadata baseBackup,
            BackupMetadata latestContinuousBackup,
            String message,
            Instant observedAt
    ) {}

    private record ChainResult(PitrStatus status, Instant from, Instant until,
                               BackupMetadata latestContinuous, String message) {}
}
