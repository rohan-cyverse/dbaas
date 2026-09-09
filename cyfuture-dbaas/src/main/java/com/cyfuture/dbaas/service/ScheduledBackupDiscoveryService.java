package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.entity.BackupMetadata;
import com.cyfuture.dbaas.entity.BackupPolicyMetadata;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.entity.OperationMetadata;
import com.cyfuture.dbaas.model.BackupStatus;
import com.cyfuture.dbaas.model.BackupTriggerMethod;
import com.cyfuture.dbaas.model.BackupType;
import com.cyfuture.dbaas.model.OperationStatus;
import com.cyfuture.dbaas.model.OperationType;
import com.cyfuture.dbaas.model.ProvisioningStage;
import com.cyfuture.dbaas.repository.BackupMetadataRepository;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import com.cyfuture.dbaas.repository.OperationMetadataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/** Imports DBaaS database's generated full and continuous backup history from KubeBlocks. */
@Service
@RequiredArgsConstructor
public class ScheduledBackupDiscoveryService {
    private final BackupMetadataRepository backupRepository;
    private final DatabaseMetadataRepository databaseRepository;
    private final OperationMetadataRepository operationRepository;
    private final KubeBlocksClient kubeBlocksClient;
    private final BackupRetentionService retentionService;
    private final BackupEngineStrategies strategies;
    private final BackupConfigurationNormalizer normalizer;

    @Transactional
    public void discover(BackupPolicyMetadata settings) {
        if (!settings.isAutoBackupEnabled() || settings.getKubernetesPolicyName() == null) return;
        DatabaseMetadata source = databaseRepository.findByDatabaseIdAndProjectName(
                settings.getDatabaseId(), settings.getProjectName()).orElse(null);
        if (source == null) return;
        BackupEngineStrategy strategy = strategies.require(source.getEngine());
        for (KubeBlocksClient.GeneratedBackupInfo item : kubeBlocksClient.listGeneratedBackups(
                source.getNamespaceName(), source.getDatabaseId(), settings.getKubernetesPolicyName(),
                strategy.manualFullMethod(), settings.isPitrEnabled() ? strategy.continuousMethod() : null)) {
            importOne(settings, source, item);
        }
    }

    @Transactional
    void importOne(BackupPolicyMetadata settings, DatabaseMetadata source,
                   KubeBlocksClient.GeneratedBackupInfo item) {
        BackupMetadata backup = backupRepository.findByProjectNameAndDatabaseIdAndKubernetesBackupName(
                source.getProjectName(), source.getDatabaseId(), item.backupName()).orElse(null);
        if (backup != null) {
            synchronizeObserved(backup, item);
            return;
        }
        Instant now = Instant.now();
        String suffix = shortHash(item.uid());
        String backupId = (item.kind() == KubeBlocksClient.GeneratedBackupKind.FULL ? "bkp-s-" : "bkp-c-")
                + suffix;
        String operationId = "op-" + shortHash("scheduled|" + item.uid());
        BackupStatus status = status(item.phase());
        backup = new BackupMetadata();
        backup.setBackupId(backupId);
        backup.setOperationId(operationId);
        backup.setProjectName(source.getProjectName());
        backup.setDatabaseId(source.getDatabaseId());
        backup.setEngine(source.getEngine());
        backup.setBackupType(type(item.kind()));
        backup.setBackupMethod(item.backupMethod());
        backup.setTriggerMethod(BackupTriggerMethod.SCHEDULED);
        backup.setKubernetesBackupName(item.backupName());
        backup.setKubernetesUid(item.uid());
        backup.setKubernetesPolicyName(settings.getKubernetesPolicyName());
        backup.setStatus(status);
        backup.setRetentionPeriod(item.retentionPeriod() == null || item.retentionPeriod().isBlank()
                ? normalizer.duration(settings.getRetentionDays()) : item.retentionPeriod());
        backup.setSizeBytes(item.sizeBytes());
        backup.setIdempotencyKey("scheduled:" + item.uid());
        backup.setRequestHash(shortHash(item.uid() + "|" + item.backupName()));
        applyObservedFields(backup, item);
        backup.setCreatedAt(now);
        backup.setStartedAt(item.startedAt());
        backup.setCompletedAt(status == BackupStatus.COMPLETED
                ? (item.completedAt() == null ? now : item.completedAt()) : null);
        backup.setFailureCode(status == BackupStatus.FAILED ? "KUBERNETES_BACKUP_FAILED" : null);
        backup.setFailureMessage(status == BackupStatus.FAILED ? item.message() : null);
        backup.setLastObservedAt(now);
        resolveLineage(backup);
        try {
            backupRepository.save(backup);
            operationRepository.save(OperationMetadata.builder()
                    .operationId(operationId)
                    .databaseId(source.getDatabaseId())
                    .projectName(source.getProjectName())
                    .type(OperationType.BACKUP)
                    .status(operationStatus(status))
                    .provisioningStage(operationStatus(status) == OperationStatus.SUCCEEDED
                            ? ProvisioningStage.READY : operationStatus(status) == OperationStatus.FAILED
                            ? ProvisioningStage.FAILED : ProvisioningStage.WAITING_FOR_REPLICAS)
                    .progress(operationStatus(status) == OperationStatus.RUNNING ? 50 : 100)
                    .message("Scheduled backup " + status.name().toLowerCase())
                    .idempotencyKey(backup.getIdempotencyKey())
                    .requestHash(backup.getRequestHash())
                    .createdAt(now)
                    .startedAt(item.startedAt())
                    .completedAt(status == BackupStatus.COMPLETED || status == BackupStatus.FAILED ? now : null)
                    .build());
        } catch (DataIntegrityViolationException ignored) {
            return;
        }
        if (status == BackupStatus.COMPLETED && backup.getBackupType() == BackupType.FULL) {
            retentionService.recordCompletion(backupId);
        }
    }

    private void synchronizeObserved(BackupMetadata backup, KubeBlocksClient.GeneratedBackupInfo item) {
        BackupStatus status = status(item.phase());
        Instant now = Instant.now();
        backup.setStatus(status);
        if (item.backupMethod() != null && !item.backupMethod().isBlank()) backup.setBackupMethod(item.backupMethod());
        backup.setSizeBytes(item.sizeBytes());
        if (item.startedAt() != null) backup.setStartedAt(item.startedAt());
        if (status == BackupStatus.COMPLETED) {
            backup.setCompletedAt(item.completedAt() == null ? now : item.completedAt());
            backup.setFailureCode(null);
            backup.setFailureMessage(null);
        } else if (status == BackupStatus.FAILED) {
            backup.setFailureCode("KUBERNETES_BACKUP_FAILED");
            backup.setFailureMessage(item.message());
            if (backup.getCompletedAt() == null) backup.setCompletedAt(now);
        }
        applyObservedFields(backup, item);
        resolveLineage(backup);
        backup.setLastObservedAt(now);
        backupRepository.save(backup);
        if (status == BackupStatus.COMPLETED && backup.getBackupType() == BackupType.FULL) {
            retentionService.recordCompletion(backup.getBackupId());
        }
    }

    private void applyObservedFields(BackupMetadata backup, KubeBlocksClient.GeneratedBackupInfo item) {
        backup.setParentKubernetesBackupName(item.parentBackupName());
        backup.setBaseKubernetesBackupName(item.baseBackupName());
        backup.setCoverageStart(item.coverageStart());
        backup.setCoverageEnd(item.coverageEnd());
        if (item.kind() == KubeBlocksClient.GeneratedBackupKind.FULL) {
            backup.setBaseBackupId(backup.getBackupId());
            backup.setBaseKubernetesBackupName(backup.getKubernetesBackupName());
            backup.setParentBackupId(null);
        }
    }

    private void resolveLineage(BackupMetadata backup) {
        if (backup.getBackupType() == BackupType.FULL) {
            backup.setBaseBackupId(backup.getBackupId());
            backup.setBaseKubernetesBackupName(backup.getKubernetesBackupName());
            return;
        }
        if (backup.getParentKubernetesBackupName() != null) {
            backupRepository.findByProjectNameAndDatabaseIdAndKubernetesBackupName(
                    backup.getProjectName(), backup.getDatabaseId(), backup.getParentKubernetesBackupName())
                    .ifPresent(parent -> backup.setParentBackupId(parent.getBackupId()));
        }
        if (backup.getBaseKubernetesBackupName() != null) {
            backupRepository.findByProjectNameAndDatabaseIdAndKubernetesBackupName(
                    backup.getProjectName(), backup.getDatabaseId(), backup.getBaseKubernetesBackupName())
                    .ifPresent(base -> backup.setBaseBackupId(base.getBackupId()));
        }
    }

    private BackupType type(KubeBlocksClient.GeneratedBackupKind kind) {
        return kind == KubeBlocksClient.GeneratedBackupKind.CONTINUOUS
                ? BackupType.CONTINUOUS : BackupType.FULL;
    }

    private OperationStatus operationStatus(BackupStatus status) {
        return status == BackupStatus.COMPLETED ? OperationStatus.SUCCEEDED
                : status == BackupStatus.FAILED ? OperationStatus.FAILED
                : status == BackupStatus.PENDING ? OperationStatus.PENDING : OperationStatus.RUNNING;
    }

    private BackupStatus status(String phase) {
        if ("Completed".equalsIgnoreCase(phase)) return BackupStatus.COMPLETED;
        if ("Failed".equalsIgnoreCase(phase)) return BackupStatus.FAILED;
        if ("New".equalsIgnoreCase(phase) || "Pending".equalsIgnoreCase(phase)) return BackupStatus.PENDING;
        return BackupStatus.RUNNING;
    }

    private String shortHash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 20);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
