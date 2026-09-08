package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.entity.BackupMetadata;
import com.cyfuture.dbaas.entity.BackupPolicyMetadata;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.entity.OperationMetadata;
import com.cyfuture.dbaas.model.BackupDeletionMode;
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
import java.util.Objects;

/** Imports only generated BackupSchedule children into the same history pipeline as manual backups. */
@Service
@RequiredArgsConstructor
public class ScheduledBackupDiscoveryService {
    private final BackupMetadataRepository backupRepository;
    private final DatabaseMetadataRepository databaseRepository;
    private final OperationMetadataRepository operationRepository;
    private final KubeBlocksClient kubeBlocksClient;
    private final BackupRetentionService retentionService;

    @Transactional
    public void discover(BackupPolicyMetadata policy) {
        if (!policy.isAutoBackupEnabled() || policy.getKubernetesPolicyName() == null) return;
        DatabaseMetadata source = databaseRepository.findByDatabaseIdAndProjectName(
                policy.getDatabaseId(), policy.getProjectName()).orElse(null);
        if (source == null) return;
        for (KubeBlocksClient.ScheduledBackupInfo item : kubeBlocksClient.listScheduledBackups(
                source.getNamespaceName(), source.getDatabaseId(), policy.getKubernetesPolicyName())) {
            importOne(policy, source, item);
        }
    }

    @Transactional
    void importOne(BackupPolicyMetadata policy, DatabaseMetadata source,
                   KubeBlocksClient.ScheduledBackupInfo item) {
        BackupMetadata backup = backupRepository.findByKubernetesNamespaceAndKubernetesBackupName(
                source.getNamespaceName(), item.backupName()).orElse(null);
        if (backup != null) {
            synchronizeObserved(backup, item);
            return;
        }
        Instant now = Instant.now();
        String suffix = shortHash(item.uid());
        String backupId = "bkp-a-" + suffix;
        String operationId = "op-a-" + suffix;
        BackupStatus status = status(item.phase());
        backup = new BackupMetadata();
        backup.setBackupId(backupId);
        backup.setOperationId(operationId);
        backup.setProjectName(policy.getProjectName());
        backup.setDatabaseId(policy.getDatabaseId());
        backup.setSourceDisplayName(source.getDisplayName());
        backup.setEngine(source.getEngine());
        backup.setBackupType(BackupType.FULL);
        backup.setBackupMethod(item.backupMethod() == null || item.backupMethod().isBlank()
                ? policy.getDefaultBackupMethod() : item.backupMethod());
        backup.setTriggerMethod(BackupTriggerMethod.AUTOMATIC);
        backup.setBackupChainId(backupId);
        backup.setKubernetesBackupName(item.backupName());
        backup.setKubernetesNamespace(source.getNamespaceName());
        backup.setKubernetesUid(item.uid());
        backup.setKubernetesPolicyName(policy.getKubernetesPolicyName());
        backup.setBackupRepositoryName(policy.getBackupRepositoryName());
        backup.setStatus(status);
        backup.setRetentionPeriod(item.retentionPeriod() == null || item.retentionPeriod().isBlank()
                ? policy.getDefaultRetentionPeriod() : item.retentionPeriod());
        backup.setRetentionPolicy(policy.getRetentionPolicy());
        backup.setDeletionMode(BackupDeletionMode.CR_ONLY);
        backup.setSizeBytes(item.sizeBytes());
        backup.setIdempotencyKey("automatic:" + item.uid());
        backup.setRequestHash(shortHash(item.uid() + "|" + item.backupName()));
        captureSource(backup, source);
        backup.setCreatedAt(now);
        backup.setStartedAt(item.startedAt());
        backup.setCompletedAt(status == BackupStatus.COMPLETED
                ? (item.completedAt() == null ? now : item.completedAt()) : null);
        backup.setFailureCode(status == BackupStatus.FAILED ? "KUBERNETES_BACKUP_FAILED" : null);
        backup.setFailureMessage(status == BackupStatus.FAILED ? item.message() : null);
        backup.setLastObservedAt(now);
        OperationStatus operationStatus = status == BackupStatus.COMPLETED ? OperationStatus.SUCCEEDED
                : status == BackupStatus.FAILED ? OperationStatus.FAILED
                : status == BackupStatus.PENDING ? OperationStatus.PENDING : OperationStatus.RUNNING;
        try {
            backupRepository.save(backup);
            operationRepository.save(OperationMetadata.builder()
                    .operationId(operationId)
                    .databaseId(source.getDatabaseId())
                    .projectName(source.getProjectName())
                    .type(OperationType.BACKUP)
                    .status(operationStatus)
                    .provisioningStage(operationStatus == OperationStatus.SUCCEEDED
                            ? ProvisioningStage.READY : ProvisioningStage.WAITING_FOR_REPLICAS)
                    .progress(operationStatus == OperationStatus.SUCCEEDED || operationStatus == OperationStatus.FAILED
                            ? 100 : 50)
                    .message(status == BackupStatus.FAILED ? "Scheduled backup failed"
                            : status == BackupStatus.COMPLETED ? "Scheduled backup completed"
                            : "Scheduled backup discovered")
                    .idempotencyKey(backup.getIdempotencyKey())
                    .requestHash(backup.getRequestHash())
                    .createdAt(now)
                    .startedAt(item.startedAt())
                    .completedAt(status == BackupStatus.COMPLETED || status == BackupStatus.FAILED ? now : null)
                    .build());
        } catch (DataIntegrityViolationException ignored) {
            // A concurrent reconciler imported the same immutable Kubernetes UID.
            return;
        }
        if (status == BackupStatus.COMPLETED) retentionService.recordCompletion(backupId);
    }

    private void synchronizeObserved(BackupMetadata backup, KubeBlocksClient.ScheduledBackupInfo item) {
        BackupStatus status = status(item.phase());
        boolean changed = backup.getStatus() != status
                || (backup.getBackupMethod() == null && item.backupMethod() != null)
                || !Objects.equals(backup.getSizeBytes(), item.sizeBytes())
                || !Objects.equals(backup.getStartedAt(), item.startedAt())
                || (status == BackupStatus.COMPLETED && backup.getCompletedAt() == null);
        if (!changed) return;
        backup.setStatus(status);
        if (backup.getBackupMethod() == null && item.backupMethod() != null) {
            backup.setBackupMethod(item.backupMethod());
        }
        backup.setSizeBytes(item.sizeBytes());
        if (item.startedAt() != null) backup.setStartedAt(item.startedAt());
        if (status == BackupStatus.COMPLETED && backup.getCompletedAt() == null) {
            backup.setCompletedAt(item.completedAt() == null ? Instant.now() : item.completedAt());
            backup.setFailureCode(null);
            backup.setFailureMessage(null);
        } else if (status == BackupStatus.FAILED) {
            backup.setFailureCode("KUBERNETES_BACKUP_FAILED");
            backup.setFailureMessage(item.message());
            if (backup.getCompletedAt() == null) backup.setCompletedAt(Instant.now());
        }
        backup.setLastObservedAt(Instant.now());
        backupRepository.save(backup);
        operationRepository.findById(backup.getOperationId()).ifPresent(operation -> {
            OperationStatus operationStatus = status == BackupStatus.COMPLETED ? OperationStatus.SUCCEEDED
                    : status == BackupStatus.FAILED ? OperationStatus.FAILED : OperationStatus.RUNNING;
            operation.setStatus(operationStatus);
            operation.setProvisioningStage(operationStatus == OperationStatus.SUCCEEDED
                    ? ProvisioningStage.READY : operationStatus == OperationStatus.FAILED
                    ? ProvisioningStage.FAILED : ProvisioningStage.WAITING_FOR_REPLICAS);
            operation.setProgress(operationStatus == OperationStatus.RUNNING ? 50 : 100);
            operation.setMessage(status == BackupStatus.COMPLETED ? "Scheduled backup completed"
                    : status == BackupStatus.FAILED ? "Scheduled backup failed"
                    : "Scheduled backup is running");
            if (operation.getStartedAt() == null) operation.setStartedAt(Instant.now());
            if (operationStatus != OperationStatus.RUNNING) operation.setCompletedAt(Instant.now());
            operationRepository.save(operation);
        });
        if (status == BackupStatus.COMPLETED) retentionService.recordCompletion(backup.getBackupId());
    }

    private BackupStatus status(String phase) {
        if ("Completed".equalsIgnoreCase(phase)) return BackupStatus.COMPLETED;
        if ("Failed".equalsIgnoreCase(phase)) return BackupStatus.FAILED;
        if ("New".equalsIgnoreCase(phase) || "Pending".equalsIgnoreCase(phase)) return BackupStatus.PENDING;
        return BackupStatus.RUNNING;
    }

    private void captureSource(BackupMetadata backup, DatabaseMetadata source) {
        backup.setSourceMode(source.getMode());
        backup.setSourceDatabaseVersion(source.getDatabaseVersion());
        backup.setSourceLogicalDatabaseName(
                CredentialLifecycleService.managedDatabaseName(source.getDatabaseId()));
        backup.setSourceSizePlan(source.getSizePlan());
        backup.setSourceStorageGi(source.getStorageGi());
        backup.setSourceReplicas(source.getReplicas());
        backup.setSourceShards(source.getShards());
        backup.setSourceTimezone(source.getTimezone());
        backup.setSourceAllowedCidrs(source.getAllowedCidrs());
        backup.setSourceTags(source.getTags());
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
