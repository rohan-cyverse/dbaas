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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Imports only controller-generated BackupSchedule/BackupPolicy children. Full
 * backups and continuous log backups share durable history but retain distinct
 * types so a log segment can never be presented as a standalone restore point.
 */
@Service
public class ScheduledBackupDiscoveryService {
    private final BackupMetadataRepository backupRepository;
    private final DatabaseMetadataRepository databaseRepository;
    private final OperationMetadataRepository operationRepository;
    private final KubeBlocksClient kubeBlocksClient;
    private final BackupRetentionService retentionService;
    private final PitrRecoveryService pitrRecoveryService;

    @Autowired
    public ScheduledBackupDiscoveryService(BackupMetadataRepository backupRepository,
                                           DatabaseMetadataRepository databaseRepository,
                                           OperationMetadataRepository operationRepository,
                                           KubeBlocksClient kubeBlocksClient,
                                           BackupRetentionService retentionService,
                                           PitrRecoveryService pitrRecoveryService) {
        this.backupRepository = backupRepository;
        this.databaseRepository = databaseRepository;
        this.operationRepository = operationRepository;
        this.kubeBlocksClient = kubeBlocksClient;
        this.retentionService = retentionService;
        this.pitrRecoveryService = pitrRecoveryService;
    }

    /** Compatibility constructor retained for existing package-level callers. */
    ScheduledBackupDiscoveryService(BackupMetadataRepository backupRepository,
                                    DatabaseMetadataRepository databaseRepository,
                                    OperationMetadataRepository operationRepository,
                                    KubeBlocksClient kubeBlocksClient,
                                    BackupRetentionService retentionService) {
        this(backupRepository, databaseRepository, operationRepository, kubeBlocksClient, retentionService, null);
    }

    @Transactional
    public void discover(BackupPolicyMetadata policy) {
        if (!policy.isAutoBackupEnabled() || policy.getKubernetesPolicyName() == null) return;
        DatabaseMetadata source = databaseRepository.findByDatabaseIdAndProjectName(
                policy.getDatabaseId(), policy.getProjectName()).orElse(null);
        if (source == null) return;
        for (KubeBlocksClient.GeneratedBackupInfo item : kubeBlocksClient.listGeneratedBackups(
                source.getNamespaceName(), source.getDatabaseId(), policy.getKubernetesPolicyName(),
                policy.getDefaultBackupMethod(), policy.isPitrEnabled()
                        ? policy.getContinuousBackupMethod() : null)) {
            importOne(policy, source, item);
        }
        if (pitrRecoveryService != null) pitrRecoveryService.refresh(policy);
    }

    /** Compatibility bridge for legacy full-backup discovery callers. */
    @Transactional
    void importOne(BackupPolicyMetadata policy, DatabaseMetadata source,
                   KubeBlocksClient.ScheduledBackupInfo item) {
        importOne(policy, source, new KubeBlocksClient.GeneratedBackupInfo(
                item.backupName(), item.uid(), KubeBlocksClient.GeneratedBackupKind.FULL,
                item.backupMethod(), item.retentionPeriod(), item.backupName(), null, item.phase(),
                item.message(), item.sizeBytes(), item.startedAt(), item.completedAt(), null, null));
    }

    @Transactional
    void importOne(BackupPolicyMetadata policy, DatabaseMetadata source,
                   KubeBlocksClient.GeneratedBackupInfo item) {
        BackupMetadata backup = backupRepository.findByKubernetesNamespaceAndKubernetesBackupName(
                source.getNamespaceName(), item.backupName()).orElse(null);
        if (backup != null) {
            synchronizeObserved(backup, source, item);
            return;
        }
        Instant now = Instant.now();
        String suffix = shortHash(item.uid());
        String backupId = (item.kind() == KubeBlocksClient.GeneratedBackupKind.FULL ? "bkp-a-" : "bkp-c-")
                + suffix;
        String operationId = "op-a-" + suffix;
        BackupStatus status = status(item.phase());
        backup = new BackupMetadata();
        backup.setBackupId(backupId);
        backup.setOperationId(operationId);
        backup.setProjectName(policy.getProjectName());
        backup.setDatabaseId(policy.getDatabaseId());
        backup.setSourceDisplayName(source.getDisplayName());
        backup.setEngine(source.getEngine());
        backup.setBackupType(type(item.kind()));
        backup.setBackupMethod(item.backupMethod() == null || item.backupMethod().isBlank()
                ? policy.getDefaultBackupMethod() : item.backupMethod());
        // Every imported generated MySQL Backup (and every other engine) is
        // explicitly automatic; manual CRs are never imported through here.
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
        applyObservedFields(backup, item);
        backup.setCreatedAt(now);
        backup.setStartedAt(item.startedAt());
        backup.setCompletedAt(status == BackupStatus.COMPLETED
                ? (item.completedAt() == null ? now : item.completedAt()) : null);
        backup.setFailureCode(status == BackupStatus.FAILED ? "KUBERNETES_BACKUP_FAILED" : null);
        backup.setFailureMessage(status == BackupStatus.FAILED ? item.message() : null);
        backup.setLastObservedAt(now);
        resolveLineage(backup, source.getNamespaceName());
        OperationStatus operationStatus = operationStatus(status);
        try {
            backupRepository.save(backup);
            operationRepository.save(OperationMetadata.builder()
                    .operationId(operationId)
                    .databaseId(source.getDatabaseId())
                    .projectName(source.getProjectName())
                    .type(OperationType.BACKUP)
                    .status(operationStatus)
                    .provisioningStage(operationStatus == OperationStatus.SUCCEEDED
                            ? ProvisioningStage.READY : operationStatus == OperationStatus.FAILED
                            ? ProvisioningStage.FAILED : ProvisioningStage.WAITING_FOR_REPLICAS)
                    .progress(operationStatus == OperationStatus.SUCCEEDED || operationStatus == OperationStatus.FAILED
                            ? 100 : 50)
                    .message(message(item.kind(), status))
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
        if (status == BackupStatus.COMPLETED && backup.getBackupType() == BackupType.FULL) {
            retentionService.recordCompletion(backupId);
        }
    }

    private void synchronizeObserved(BackupMetadata backup, DatabaseMetadata source,
                                     KubeBlocksClient.GeneratedBackupInfo item) {
        BackupStatus status = status(item.phase());
        Instant now = Instant.now();
        boolean changed = backup.getStatus() != status
                || backup.getBackupType() != type(item.kind())
                || !Objects.equals(backup.getBackupMethod(), item.backupMethod())
                || !Objects.equals(backup.getSizeBytes(), item.sizeBytes())
                || !Objects.equals(backup.getStartedAt(), item.startedAt())
                || !Objects.equals(backup.getCompletedAt(), item.completedAt())
                || !Objects.equals(backup.getParentKubernetesBackupName(), item.parentBackupName())
                || !Objects.equals(backup.getBaseKubernetesBackupName(), item.baseBackupName())
                || !Objects.equals(backup.getCoverageStart(), item.coverageStart())
                || !Objects.equals(backup.getCoverageEnd(), item.coverageEnd());
        if (!changed) return;
        backup.setStatus(status);
        backup.setBackupType(type(item.kind()));
        if (item.backupMethod() != null && !item.backupMethod().isBlank()) {
            backup.setBackupMethod(item.backupMethod());
        }
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
        resolveLineage(backup, source.getNamespaceName());
        backup.setLastObservedAt(now);
        backupRepository.save(backup);
        operationRepository.findById(backup.getOperationId()).ifPresent(operation -> {
            OperationStatus operationStatus = operationStatus(status);
            operation.setStatus(operationStatus);
            operation.setProvisioningStage(operationStatus == OperationStatus.SUCCEEDED
                    ? ProvisioningStage.READY : operationStatus == OperationStatus.FAILED
                    ? ProvisioningStage.FAILED : ProvisioningStage.WAITING_FOR_REPLICAS);
            operation.setProgress(operationStatus == OperationStatus.RUNNING ? 50 : 100);
            operation.setMessage(message(item.kind(), status));
            if (operation.getStartedAt() == null) operation.setStartedAt(now);
            if (operationStatus != OperationStatus.RUNNING) operation.setCompletedAt(now);
            operationRepository.save(operation);
        });
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
            backup.setBackupChainId(backup.getBackupId());
        }
    }

    private void resolveLineage(BackupMetadata backup, String namespace) {
        if (backup.getBackupType() == BackupType.FULL) {
            backup.setBaseBackupId(backup.getBackupId());
            backup.setBaseKubernetesBackupName(backup.getKubernetesBackupName());
            return;
        }
        BackupMetadata parent = find(namespace, backup.getParentKubernetesBackupName());
        BackupMetadata base = find(namespace, backup.getBaseKubernetesBackupName());
        if (parent != null) {
            backup.setParentBackupId(parent.getBackupId());
            if (base == null && parent.getBaseBackupId() != null) {
                base = backupRepository.findById(parent.getBaseBackupId()).orElse(null);
            }
        }
        if (base != null) {
            backup.setBaseBackupId(base.getBackupId());
            backup.setBaseKubernetesBackupName(base.getKubernetesBackupName());
            backup.setBackupChainId(base.getBackupChainId() == null ? base.getBackupId() : base.getBackupChainId());
        }
    }

    private BackupMetadata find(String namespace, String name) {
        if (name == null || name.isBlank()) return null;
        return backupRepository.findByKubernetesNamespaceAndKubernetesBackupName(namespace, name).orElse(null);
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

    private String message(KubeBlocksClient.GeneratedBackupKind kind, BackupStatus status) {
        String prefix = kind == KubeBlocksClient.GeneratedBackupKind.CONTINUOUS
                ? "Continuous log backup" : "Scheduled backup";
        return status == BackupStatus.FAILED ? prefix + " failed"
                : status == BackupStatus.COMPLETED ? prefix + " completed"
                : prefix + " is running";
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
