package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.dto.BackupResponse;
import com.cyfuture.dbaas.dto.CreateBackupRequest;
import com.cyfuture.dbaas.entity.BackupMetadata;
import com.cyfuture.dbaas.entity.BackupPolicyMetadata;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.entity.OperationMetadata;
import com.cyfuture.dbaas.exception.ApiException;
import com.cyfuture.dbaas.model.BackupStatus;
import com.cyfuture.dbaas.model.BackupTriggerMethod;
import com.cyfuture.dbaas.model.BackupType;
import com.cyfuture.dbaas.model.DatabaseStatus;
import com.cyfuture.dbaas.model.OperationStatus;
import com.cyfuture.dbaas.model.OperationType;
import com.cyfuture.dbaas.model.ProvisioningStage;
import com.cyfuture.dbaas.repository.BackupMetadataRepository;
import com.cyfuture.dbaas.repository.BackupPolicyMetadataRepository;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import com.cyfuture.dbaas.repository.OperationMetadataRepository;
import com.cyfuture.dbaas.repository.RestoreRequestMetadataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** Coordinates idempotent manual full and incremental backups. KubeBlocks remains the live authority. */
@Service
@RequiredArgsConstructor
public class BackupService {
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9._:-]{8,128}$");

    private final DatabaseMetadataRepository databaseRepository;
    private final BackupMetadataRepository backupRepository;
    private final BackupPolicyMetadataRepository policyRepository;
    private final RestoreRequestMetadataRepository restoreRepository;
    private final OperationMetadataRepository operationRepository;
    private final ProjectService projectService;
    private final BackupEngineStrategies strategies;
    private final BackupConfigurationNormalizer configurationNormalizer;
    private final KubeBlocksClient kubeBlocksClient;
    private final BackupSubmissionService submissionService;
    private final BackupDeletionSubmitter deletionSubmitter;
    private final BackupPolicyReconciler policyReconciler;
    private final BackupReconciler backupReconciler;

    @Transactional
    public BackupResponse create(String project, String databaseId,
                                 String idempotencyKey, CreateBackupRequest request) {
        projectService.requireActiveProject(project);
        validateIdempotencyKey(idempotencyKey);
        BackupType type = requestedType(request);
        int retentionDays = request.retentionDays() == null
                ? defaultRetentionDays(project, databaseId) : request.retentionDays();
        String requestHash = hash(type.name(), String.valueOf(retentionDays));
        BackupMetadata duplicate = backupRepository
                .findByProjectNameAndDatabaseIdAndIdempotencyKey(project, databaseId, idempotencyKey)
                .orElse(null);
        if (duplicate != null) return duplicateResponse(duplicate, requestHash);

        DatabaseMetadata database = databaseRepository
                .findByDatabaseIdAndProjectNameForUpdate(databaseId, project)
                .orElseThrow(() -> databaseNotFound(databaseId));
        BackupEngineStrategy strategy = strategies.require(database.getEngine());
        validateSource(database, type, strategy);
        rejectActiveWork(project, databaseId);
        BackupMetadata parent = type == BackupType.INCREMENTAL
                ? requireIncrementalParent(project, databaseId) : null;

        kubeBlocksClient.validateReadyBackupRepository(configurationNormalizer.repositoryName());
        Instant now = Instant.now();
        String backupId = "bkp-" + shortId();
        String operationId = "op-" + shortId();
        BackupMetadata backup = new BackupMetadata();
        backup.setBackupId(backupId);
        backup.setOperationId(operationId);
        backup.setProjectName(project);
        backup.setDatabaseId(databaseId);
        backup.setEngine(database.getEngine());
        backup.setBackupType(type);
        backup.setBackupMethod(strategy.manualMethod(type));
        backup.setTriggerMethod(BackupTriggerMethod.MANUAL);
        backup.setKubernetesBackupName(backupId);
        applyLineage(backup, parent);
        backup.setStatus(BackupStatus.PENDING);
        backup.setRetentionPeriod(configurationNormalizer.duration(retentionDays));
        backup.setIdempotencyKey(idempotencyKey);
        backup.setRequestHash(requestHash);
        backup.setCreatedAt(now);

        OperationMetadata operation = OperationMetadata.builder()
                .operationId(operationId)
                .databaseId(databaseId)
                .projectName(project)
                .type(OperationType.BACKUP)
                .status(OperationStatus.PENDING)
                .provisioningStage(ProvisioningStage.QUEUED)
                .progress(0)
                .message("Backup request queued")
                .idempotencyKey(idempotencyKey)
                .requestHash(requestHash)
                .createdAt(now)
                .build();
        try {
            backupRepository.save(backup);
            operationRepository.save(operation);
        } catch (DataIntegrityViolationException exception) {
            BackupMetadata existing = backupRepository
                    .findByProjectNameAndDatabaseIdAndIdempotencyKey(project, databaseId, idempotencyKey)
                    .orElseThrow(() -> exception);
            return duplicateResponse(existing, requestHash);
        }
        submitAfterCommit(() -> submissionService.submit(backupId));
        return response(backup);
    }

    public List<BackupResponse> list(String project, String databaseId) {
        projectService.requireActiveProject(project);
        refresh(project, databaseId);
        return backupRepository.findByProjectNameAndDatabaseIdOrderByCreatedAtDesc(project, databaseId).stream()
                .filter(this::isUserVisibleBackup)
                .map(this::response)
                .toList();
    }

    public BackupResponse get(String project, String databaseId, String backupId) {
        projectService.requireActiveProject(project);
        BackupMetadata backup = requireBackup(project, databaseId, backupId);
        refresh(backup);
        return response(backupRepository.findById(backupId).orElse(backup));
    }

    /** DELETE has one meaning: remove the known backup and its retained data. */
    @Transactional
    public BackupResponse delete(String project, String databaseId, String backupId) {
        projectService.requireActiveProject(project);
        BackupMetadata backup = requireBackup(project, databaseId, backupId);
        if (backup.getStatus() == BackupStatus.DELETED || backup.getStatus() == BackupStatus.EXPIRED
                || backup.getStatus() == BackupStatus.DELETING) {
            return response(backup);
        }
        if (restoreRepository.existsByProjectNameAndSourceBackupIdAndStatusIn(project, backupId,
                List.of(com.cyfuture.dbaas.model.RestoreStatus.PENDING,
                        com.cyfuture.dbaas.model.RestoreStatus.RUNNING))) {
            throw new ApiException(HttpStatus.CONFLICT, "BACKUP_RESTORE_IN_PROGRESS", false,
                    "The backup cannot be deleted while a restore is running.");
        }
        backup.setStatus(BackupStatus.DELETING);
        backup.setDeleteRequestedAt(Instant.now());
        backup.setFailureCode(null);
        backup.setFailureMessage(null);
        backupRepository.save(backup);
        submitAfterCommit(() -> deletionSubmitter.delete(backupId));
        return response(backup);
    }

    private int defaultRetentionDays(String project, String databaseId) {
        return policyRepository.findByProjectNameAndDatabaseId(project, databaseId)
                .map(BackupPolicyMetadata::getRetentionDays)
                .filter(days -> days > 0)
                .orElse(configurationNormalizer.defaults().retentionDays());
    }

    private void validateSource(DatabaseMetadata database, BackupType type, BackupEngineStrategy strategy) {
        if (!strategy.supportsNow(type) || blank(strategy.manualMethod(type))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "FEATURE_NOT_AVAILABLE", false,
                    "The requested backup type is not available for this database engine.");
        }
        if (!strategy.supportsTopology(database.getMode())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "BACKUP_TOPOLOGY_UNSUPPORTED", false,
                    "The installed backup template does not support this database topology.");
        }
        if (database.getStatus() != DatabaseStatus.RUNNING) {
            throw new ApiException(HttpStatus.CONFLICT, "DATABASE_NOT_READY", true,
                    "The database must be RUNNING before a backup can start.");
        }
    }

    private BackupMetadata requireIncrementalParent(String project, String databaseId) {
        return backupRepository.findByProjectNameAndDatabaseIdAndStatusOrderByCompletedAtDesc(
                        project, databaseId, BackupStatus.COMPLETED)
                .stream()
                .filter(this::isUserVisibleBackup)
                .filter(backup -> !blank(backup.getKubernetesBackupName()))
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "INCREMENTAL_BASE_BACKUP_REQUIRED", false,
                        "A completed full backup is required before an incremental backup can start."));
    }

    private void applyLineage(BackupMetadata backup, BackupMetadata parent) {
        if (parent == null) {
            backup.setBaseBackupId(backup.getBackupId());
            backup.setBaseKubernetesBackupName(backup.getKubernetesBackupName());
            return;
        }
        backup.setParentBackupId(parent.getBackupId());
        backup.setParentKubernetesBackupName(parent.getKubernetesBackupName());
        backup.setBaseBackupId(blank(parent.getBaseBackupId()) ? parent.getBackupId() : parent.getBaseBackupId());
        backup.setBaseKubernetesBackupName(blank(parent.getBaseKubernetesBackupName())
                ? parent.getKubernetesBackupName() : parent.getBaseKubernetesBackupName());
    }

    private void rejectActiveWork(String project, String databaseId) {
        operationRepository.findByDatabaseIdAndProjectNameAndStatusIn(databaseId, project,
                        List.of(OperationStatus.PENDING, OperationStatus.RUNNING))
                .stream().findFirst().ifPresent(operation -> {
                    throw new ApiException(HttpStatus.CONFLICT, "DATABASE_OPERATION_IN_PROGRESS", false,
                            "Another database operation is already running.");
                });
        if (restoreRepository.existsByProjectNameAndSourceDatabaseIdAndStatusIn(project, databaseId,
                List.of(com.cyfuture.dbaas.model.RestoreStatus.PENDING,
                        com.cyfuture.dbaas.model.RestoreStatus.RUNNING))) {
            throw new ApiException(HttpStatus.CONFLICT, "RESTORE_IN_PROGRESS", false,
                    "A restore using this database is already running.");
        }
    }

    private BackupResponse duplicateResponse(BackupMetadata backup, String requestHash) {
        if (!requestHash.equals(backup.getRequestHash())) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", false,
                    "This Idempotency-Key was already used with a different backup request.");
        }
        return response(backup);
    }

    private BackupMetadata requireBackup(String project, String databaseId, String backupId) {
        return backupRepository.findByBackupIdAndProjectNameAndDatabaseId(backupId, project, databaseId)
                .filter(this::isUserVisibleBackup)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "BACKUP_NOT_FOUND", false,
                        "Backup was not found for this database."));
    }

    private BackupType requestedType(CreateBackupRequest request) {
        if (request == null || request.type() == null
                || (request.type() != BackupType.FULL && request.type() != BackupType.INCREMENTAL)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BACKUP_TYPE", false,
                    "type must be FULL or INCREMENTAL.");
        }
        return request.type();
    }

    private boolean isUserVisibleBackup(BackupMetadata backup) {
        return backup.getBackupType() == BackupType.FULL || backup.getBackupType() == BackupType.INCREMENTAL;
    }

    public BackupResponse response(BackupMetadata backup) {
        return new BackupResponse(backup.getBackupId(), backup.getBackupType(),
                backup.getTriggerMethod() == null ? BackupTriggerMethod.MANUAL : backup.getTriggerMethod(),
                backup.getStatus(), backup.getSizeBytes(), backup.getCreatedAt(), backup.getStartedAt(),
                backup.getCompletedAt(), backup.getExpiresAt(), backup.getDeletedAt(),
                backup.getFailureCode(), backup.getFailureMessage());
    }

    private void refresh(String project, String databaseId) {
        policyRepository.findByProjectNameAndDatabaseId(project, databaseId).ifPresent(policyReconciler::refresh);
        backupRepository.findByProjectNameAndDatabaseIdOrderByCreatedAtDesc(project, databaseId)
                .forEach(this::refresh);
    }

    private void refresh(BackupMetadata backup) {
        try {
            backupReconciler.refresh(backup);
        } catch (Exception exception) {
            if (!BackupRestoreSafety.retryable(exception)) throw exception;
        }
    }

    private void validateIdempotencyKey(String value) {
        if (value == null || !IDEMPOTENCY_KEY.matcher(value).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY", false,
                    "Idempotency-Key must be 8-128 characters using letters, numbers, '.', '_', ':' or '-'.");
        }
    }

    private ApiException databaseNotFound(String databaseId) {
        return new ApiException(HttpStatus.NOT_FOUND, "DATABASE_NOT_FOUND", false,
                "Database " + databaseId + " was not found in this project.");
    }

    private void submitAfterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { action.run(); }
        });
    }

    private String hash(String... values) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(String.join("|", values).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }
}
