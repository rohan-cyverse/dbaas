package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.config.DatabaseProperties;
import com.cyfuture.dbaas.dto.BackupAcceptedResponse;
import com.cyfuture.dbaas.dto.BackupResponse;
import com.cyfuture.dbaas.dto.CreateBackupRequest;
import com.cyfuture.dbaas.entity.BackupMetadata;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.entity.OperationMetadata;
import com.cyfuture.dbaas.exception.ApiException;
import com.cyfuture.dbaas.model.BackupStatus;
import com.cyfuture.dbaas.model.BackupType;
import com.cyfuture.dbaas.model.DatabaseStatus;
import com.cyfuture.dbaas.model.OperationStatus;
import com.cyfuture.dbaas.model.OperationType;
import com.cyfuture.dbaas.model.ProvisioningStage;
import com.cyfuture.dbaas.repository.BackupMetadataRepository;
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

@Service
@RequiredArgsConstructor
public class BackupService {
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9._:-]{8,128}$");
    private static final Pattern RETENTION = Pattern.compile("^(?:[1-9][0-9]*(?:y|mo|d|h|m))+$");

    private final DatabaseMetadataRepository databaseRepository;
    private final BackupMetadataRepository backupRepository;
    private final RestoreRequestMetadataRepository restoreRepository;
    private final OperationMetadataRepository operationRepository;
    private final ProjectService projectService;
    private final DatabaseProperties properties;
    private final BackupEngineStrategies strategies;
    private final BackupSubmissionService submissionService;
    private final BackupPurgeSubmitter purgeSubmitter;

    @Transactional
    public BackupAcceptedResponse create(String project, String databaseId,
                                         String idempotencyKey, CreateBackupRequest request) {
        projectService.requireActiveProject(project);
        validateIdempotencyKey(idempotencyKey);
        BackupType type = request == null || request.type() == null ? BackupType.FULL : request.type();
        String retention = request == null || blank(request.retention())
                ? properties.getBackup().getDefaultRetention() : request.retention();
        validateRetention(retention);

        BackupMetadata duplicate = backupRepository
                .findByProjectNameAndDatabaseIdAndIdempotencyKey(project, databaseId, idempotencyKey)
                .orElse(null);
        String requestHash = hash(type.name(), retention);
        if (duplicate != null) return duplicateResponse(duplicate, requestHash);

        DatabaseMetadata database = databaseRepository
                .findByDatabaseIdAndProjectNameForUpdate(databaseId, project)
                .orElseThrow(() -> databaseNotFound(databaseId));
        validateSource(database, type);
        rejectActiveWork(project, databaseId);

        Instant now = Instant.now();
        String backupId = "bkp-" + shortId();
        String operationId = "op-" + shortId();
        BackupMetadata backup = new BackupMetadata();
        backup.setBackupId(backupId);
        backup.setOperationId(operationId);
        backup.setProjectName(project);
        backup.setDatabaseId(databaseId);
        backup.setSourceDisplayName(database.getDisplayName());
        backup.setEngine(database.getEngine());
        backup.setBackupType(type);
        backup.setBackupChainId(backupId);
        backup.setKubernetesBackupName(backupId);
        backup.setBackupRepositoryName(properties.getBackup().getRepositoryName());
        backup.setStatus(BackupStatus.PENDING);
        backup.setRetentionPeriod(retention);
        backup.setIdempotencyKey(idempotencyKey);
        backup.setRequestHash(requestHash);
        captureSource(backup, database);
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
        return accepted(backup);
    }

    public List<BackupResponse> list(String project, String databaseId) {
        projectService.requireActiveProject(project);
        return backupRepository.findByProjectNameAndDatabaseIdOrderByCreatedAtDesc(project, databaseId)
                .stream().map(this::response).toList();
    }

    public BackupResponse get(String project, String databaseId, String backupId) {
        projectService.requireActiveProject(project);
        return response(requireBackup(project, databaseId, backupId));
    }

    @Transactional
    public BackupResponse purge(String project, String databaseId, String backupId) {
        projectService.requireActiveProject(project);
        BackupMetadata backup = requireBackup(project, databaseId, backupId);
        if (backup.getStatus() == BackupStatus.DELETED) return response(backup);
        if (backup.getStatus() == BackupStatus.DELETING) return response(backup);
        if (restoreRepository.existsByProjectNameAndSourceBackupIdAndStatusIn(project, backupId,
                List.of(com.cyfuture.dbaas.model.RestoreStatus.PENDING,
                        com.cyfuture.dbaas.model.RestoreStatus.RUNNING))) {
            throw new ApiException(HttpStatus.CONFLICT, "BACKUP_RESTORE_IN_PROGRESS", false,
                    "The backup cannot be purged while a restore is running.");
        }
        Instant now = Instant.now();
        String deleteOperationId = "op-" + shortId();
        backup.setDeleteOperationId(deleteOperationId);
        backup.setStatus(BackupStatus.DELETING);
        backup.setDeleteRequestedAt(now);
        backup.setFailureCode(null);
        backup.setFailureMessage(null);
        operationRepository.save(OperationMetadata.builder()
                .operationId(deleteOperationId)
                .databaseId(databaseId)
                .projectName(project)
                .type(OperationType.BACKUP_DELETE)
                .status(OperationStatus.PENDING)
                .provisioningStage(ProvisioningStage.QUEUED)
                .progress(0)
                .message("Backup purge queued")
                .createdAt(now)
                .build());
        backupRepository.save(backup);
        submitAfterCommit(() -> purgeSubmitter.purge(backupId));
        return response(backup);
    }

    private void validateSource(DatabaseMetadata database, BackupType type) {
        BackupEngineStrategy strategy = strategies.require(database.getEngine());
        if (!strategy.supportsNow(type)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "BACKUP_TYPE_NOT_ENABLED", false,
                    "Only manual FULL backups are enabled at this time.");
        }
        if (database.getStatus() != DatabaseStatus.RUNNING) {
            throw new ApiException(HttpStatus.CONFLICT, "DATABASE_NOT_READY", true,
                    "The database must be RUNNING before a backup can start.");
        }
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

    private void captureSource(BackupMetadata backup, DatabaseMetadata source) {
        backup.setSourceMode(source.getMode());
        backup.setSourceDatabaseVersion(source.getDatabaseVersion());
        backup.setSourceSizePlan(source.getSizePlan());
        backup.setSourceStorageGi(source.getStorageGi());
        backup.setSourceReplicas(source.getReplicas());
        backup.setSourceShards(source.getShards());
        backup.setSourceTimezone(source.getTimezone());
        backup.setSourceAllowedCidrs(source.getAllowedCidrs());
        backup.setSourceTags(source.getTags());
    }

    private BackupAcceptedResponse duplicateResponse(BackupMetadata backup, String requestHash) {
        if (!requestHash.equals(backup.getRequestHash())) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", false,
                    "This Idempotency-Key was already used with a different backup request.");
        }
        return accepted(backup);
    }

    private BackupAcceptedResponse accepted(BackupMetadata backup) {
        String path = "/api/v1/projects/" + backup.getProjectName() + "/databases/"
                + backup.getDatabaseId() + "/backups/" + backup.getBackupId();
        return new BackupAcceptedResponse(backup.getOperationId(), backup.getBackupId(), backup.getStatus(),
                path, Math.max(1, properties.getBackup().getPollAfterSeconds()));
    }

    private BackupMetadata requireBackup(String project, String databaseId, String backupId) {
        return backupRepository.findByBackupIdAndProjectNameAndDatabaseId(backupId, project, databaseId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "BACKUP_NOT_FOUND", false,
                        "Backup was not found for this database."));
    }

    private BackupResponse response(BackupMetadata backup) {
        return new BackupResponse(backup.getBackupId(), backup.getOperationId(), backup.getDatabaseId(),
                backup.getEngine(), backup.getBackupType(), backup.getParentBackupId(), backup.getBackupChainId(),
                backup.getStatus(), backup.getRetentionPeriod(), backup.getSizeBytes(), message(backup),
                backup.getCreatedAt(), backup.getStartedAt(), backup.getCompletedAt());
    }

    private String message(BackupMetadata backup) {
        return switch (backup.getStatus()) {
            case PENDING -> "Backup request is queued.";
            case RUNNING -> "Backup is running.";
            case COMPLETED -> "Backup completed.";
            case DELETING -> "Backup purge is running.";
            case DELETED -> "Backup was purged.";
            case FAILED -> backup.getFailureMessage() == null || backup.getFailureMessage().isBlank()
                    ? "Backup failed." : backup.getFailureMessage();
        };
    }

    private void validateIdempotencyKey(String value) {
        if (value == null || !IDEMPOTENCY_KEY.matcher(value).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY", false,
                    "Idempotency-Key must be 8-128 characters using letters, numbers, '.', '_', ':' or '-'.");
        }
    }

    private void validateRetention(String value) {
        if (value == null || !RETENTION.matcher(value).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BACKUP_RETENTION", false,
                    "Retention must use a KubeBlocks duration such as 7d or 1mo7d.");
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

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
