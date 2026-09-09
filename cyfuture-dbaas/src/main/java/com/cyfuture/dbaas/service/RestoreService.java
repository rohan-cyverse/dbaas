package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.dto.CreateRestoreRequest;
import com.cyfuture.dbaas.dto.RestoreResponse;
import com.cyfuture.dbaas.entity.BackupMetadata;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.entity.OperationMetadata;
import com.cyfuture.dbaas.entity.ProjectMetadata;
import com.cyfuture.dbaas.entity.RestoreRequestMetadata;
import com.cyfuture.dbaas.exception.ApiException;
import com.cyfuture.dbaas.model.BackupStatus;
import com.cyfuture.dbaas.model.BackupType;
import com.cyfuture.dbaas.model.DatabaseMode;
import com.cyfuture.dbaas.model.DatabaseStatus;
import com.cyfuture.dbaas.model.DesiredState;
import com.cyfuture.dbaas.model.OperationStatus;
import com.cyfuture.dbaas.model.OperationType;
import com.cyfuture.dbaas.model.ProvisioningStage;
import com.cyfuture.dbaas.model.RestoreMode;
import com.cyfuture.dbaas.model.RestoreStatus;
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

/** Creates a new database for every full or point-in-time restore. */
@Service
@RequiredArgsConstructor
public class RestoreService {
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9._:-]{8,128}$");

    private final BackupMetadataRepository backupRepository;
    private final RestoreRequestMetadataRepository restoreRepository;
    private final DatabaseMetadataRepository databaseRepository;
    private final OperationMetadataRepository operationRepository;
    private final ProjectService projectService;
    private final BackupEngineStrategies strategies;
    private final RestoreSubmissionService submissionService;
    private final RestoreReconciler restoreReconciler;
    private final PitrRecoveryService pitrRecoveryService;
    private final BackupPolicyService backupPolicyService;

    @Transactional
    public RestoreResponse restore(String project, String databaseId,
                                   String idempotencyKey, CreateRestoreRequest request) {
        projectService.requireActiveProject(project);
        validateIdempotencyKey(idempotencyKey);
        if (request == null || request.mode() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RESTORE_MODE", false,
                    "mode must be FULL or POINT_IN_TIME.");
        }
        DatabaseMetadata source = databaseRepository.findByDatabaseIdAndProjectNameForUpdate(databaseId, project)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "DATABASE_NOT_FOUND", false,
                        "Database was not found in this project."));
        if (request.mode() == RestoreMode.FULL) {
            if (blank(request.backupId()) || !blank(request.restoreTime())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RESTORE_MODE", false,
                        "FULL restore requires backupId and does not accept restoreTime.");
            }
            return restoreFull(project, source, idempotencyKey, request.backupId());
        }
        if (request.mode() == RestoreMode.POINT_IN_TIME) {
            if (!blank(request.backupId()) || blank(request.restoreTime())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RESTORE_MODE", false,
                        "POINT_IN_TIME restore requires restoreTime and does not accept backupId.");
            }
            return restorePointInTime(project, source, idempotencyKey, request.restoreTime());
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RESTORE_MODE", false,
                "mode must be FULL or POINT_IN_TIME.");
    }

    public List<RestoreResponse> list(String project, String databaseId) {
        projectService.requireActiveProject(project);
        return restoreRepository.findByProjectNameAndSourceDatabaseIdOrderByCreatedAtDesc(project, databaseId)
                .stream().map(this::refreshAndRespond).toList();
    }

    public RestoreResponse get(String project, String databaseId, String restoreId) {
        projectService.requireActiveProject(project);
        RestoreRequestMetadata restore = restoreRepository
                .findByRestoreIdAndProjectNameAndSourceDatabaseId(restoreId, project, databaseId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RESTORE_NOT_FOUND", false,
                        "Restore was not found for this database."));
        return refreshAndRespond(restore);
    }

    private RestoreResponse restoreFull(String project, DatabaseMetadata source,
                                        String idempotencyKey, String backupId) {
        String requestHash = hash(RestoreMode.FULL.name(), backupId);
        RestoreResponse duplicate = duplicateForSource(project, source.getDatabaseId(), idempotencyKey, requestHash);
        if (duplicate != null) return duplicate;
        BackupMetadata backup = backupRepository.findByBackupIdAndProjectNameAndDatabaseId(
                        backupId, project, source.getDatabaseId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "BACKUP_NOT_FOUND", false,
                        "Backup was not found for this database."));
        validateFullBackup(backup, source);
        return createRestore(project, source, idempotencyKey, backup, RestoreMode.FULL, null, requestHash);
    }

    private RestoreResponse restorePointInTime(String project, DatabaseMetadata source,
                                               String idempotencyKey, String restoreTimeValue) {
        BackupEngineStrategy strategy = strategies.require(source.getEngine());
        if (!strategy.supportsPitrTopology(source.getMode())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PITR_NOT_SUPPORTED", false,
                    "Point-in-time recovery is not supported for this database topology.");
        }
        Instant restoreTime = parseUtcRestoreTime(restoreTimeValue);
        if (!restoreTime.isBefore(Instant.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RESTORE_TIME", false,
                    "restoreTime must be a past UTC timestamp.");
        }
        String requestHash = hash(RestoreMode.POINT_IN_TIME.name(), restoreTime.toString());
        RestoreResponse duplicate = duplicateForSource(project, source.getDatabaseId(), idempotencyKey, requestHash);
        if (duplicate != null) return duplicate;
        backupPolicyService.refresh(project, source.getDatabaseId());
        PitrRecoveryService.PitrWindow window = pitrRecoveryService.requireRestoreWindow(project, source.getDatabaseId());
        if (restoreTime.isBefore(window.startsAt()) || restoreTime.isAfter(window.endsAt())) {
            throw new ApiException(HttpStatus.CONFLICT, "RESTORE_TIME_OUTSIDE_WINDOW", false,
                    "restoreTime is outside the currently recoverable window.");
        }
        return createRestore(project, source, idempotencyKey, window.baseBackup(),
                RestoreMode.POINT_IN_TIME, restoreTime, requestHash);
    }

    private RestoreResponse createRestore(String project, DatabaseMetadata source, String idempotencyKey,
                                          BackupMetadata backup, RestoreMode mode, Instant restoreTime,
                                          String requestHash) {
        ProjectMetadata projectMetadata = projectService.requireActiveProject(project);
        String restoreId = "rst-" + shortId();
        String restoredDatabaseId = "db-" + shortId();
        String operationId = "op-" + shortId();
        Instant now = Instant.now();
        DatabaseMetadata target = restoredDatabase(source, project, restoredDatabaseId,
                uniqueDisplayName(project, restoreId));
        target.setOperationId(operationId);
        target.setIdempotencyKey("restore:" + restoreId);
        target.setRequestHash(requestHash);
        target.setNamespaceName(projectMetadata.getNamespaceName());
        target.setStatus(DatabaseStatus.PROVISIONING);
        target.setDesiredState(DesiredState.RUNNING);
        target.setProvisioningStage(ProvisioningStage.RESTORING_DATA);
        target.setProgress(5);
        target.setMessage("Restore request queued");
        target.setCreatedAt(now);
        target.setUpdatedAt(now);

        RestoreRequestMetadata restore = new RestoreRequestMetadata();
        restore.setRestoreId(restoreId);
        restore.setOperationId(operationId);
        restore.setProjectName(project);
        restore.setSourceDatabaseId(source.getDatabaseId());
        restore.setSourceBackupId(backup.getBackupId());
        restore.setRestoreMode(mode);
        restore.setRestoredDatabaseId(restoredDatabaseId);
        restore.setRestoreTime(restoreTime);
        restore.setKubernetesOpsRequestName(restoreId);
        restore.setStatus(RestoreStatus.PENDING);
        restore.setIdempotencyKey(idempotencyKey);
        restore.setRequestHash(requestHash);
        restore.setCreatedAt(now);

        OperationMetadata operation = OperationMetadata.builder()
                .operationId(operationId)
                .databaseId(restoredDatabaseId)
                .projectName(project)
                .type(OperationType.RESTORE)
                .status(OperationStatus.PENDING)
                .provisioningStage(ProvisioningStage.RESTORING_DATA)
                .progress(5)
                .message(mode == RestoreMode.POINT_IN_TIME ? "Point-in-time restore queued" : "Restore queued")
                .idempotencyKey(idempotencyKey)
                .requestHash(requestHash)
                .opsRequestName(restore.getKubernetesOpsRequestName())
                .createdAt(now)
                .build();
        try {
            databaseRepository.save(target);
            restoreRepository.save(restore);
            operationRepository.save(operation);
        } catch (DataIntegrityViolationException exception) {
            RestoreRequestMetadata existing = restoreRepository
                    .findByProjectNameAndSourceDatabaseIdAndIdempotencyKey(project, source.getDatabaseId(), idempotencyKey)
                    .orElseThrow(() -> exception);
            return duplicateResponse(existing, requestHash);
        }
        submitAfterCommit(() -> submissionService.submit(restoreId));
        return response(restore);
    }

    private void validateFullBackup(BackupMetadata backup, DatabaseMetadata source) {
        if (backup.getStatus() != BackupStatus.COMPLETED
                || (backup.getBackupType() != BackupType.FULL
                && backup.getBackupType() != BackupType.INCREMENTAL)) {
            throw new ApiException(HttpStatus.CONFLICT, "BACKUP_NOT_AVAILABLE", false,
                    "Only a completed full or incremental backup can be restored.");
        }
        if (blank(backup.getKubernetesBackupName())) {
            throw new ApiException(HttpStatus.CONFLICT, "BACKUP_NOT_AVAILABLE", false,
                    "Backup correlation is unavailable.");
        }
        BackupEngineStrategy strategy = strategies.require(source.getEngine());
        String expectedMethod = strategy.manualMethod(backup.getBackupType());
        if (blank(expectedMethod) || !expectedMethod.equals(backup.getBackupMethod())
                || !strategy.supportsTopology(source.getMode())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "BACKUP_NOT_RESTORABLE", false,
                    "This backup is not compatible with the current database topology.");
        }
    }

    private DatabaseMetadata restoredDatabase(DatabaseMetadata source, String project,
                                               String databaseId, String displayName) {
        DatabaseMetadata target = new DatabaseMetadata();
        target.setDatabaseId(databaseId);
        target.setProjectName(project);
        target.setDisplayName(displayName);
        target.setRemark("Restored database");
        target.setEngine(source.getEngine());
        target.setMode(source.getMode());
        target.setDatabaseVersion(source.getDatabaseVersion());
        target.setSizePlan(source.getSizePlan());
        target.setStorageGi(source.getStorageGi());
        target.setReplicas(source.getReplicas());
        target.setShards(source.getShards());
        target.setExpectedReplicas(source.getMode() == DatabaseMode.SHARDING
                ? source.getShards() * source.getReplicas() + 5 : source.getReplicas());
        target.setObservedReadyReplicas(0);
        target.setObservedServiceReady(false);
        target.setTimezone(source.getTimezone());
        target.setAllowedCidrs(source.getAllowedCidrs());
        target.setTags(source.getTags());
        target.setDeletionProtection(false);
        return target;
    }

    private String uniqueDisplayName(String project, String restoreId) {
        String base = "restore-" + restoreId.substring(4);
        if (!databaseRepository.existsByProjectNameAndDisplayName(project, base)) return base;
        for (int i = 0; i < 12; i++) {
            String suffix = "-" + shortId().substring(0, 4);
            String candidate = base.substring(0, Math.min(base.length(), 32 - suffix.length())) + suffix;
            if (!databaseRepository.existsByProjectNameAndDisplayName(project, candidate)) return candidate;
        }
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_NAME_ALLOCATION_FAILED", true,
                "Unable to allocate a name for the restored database. Retry the request.");
    }

    private Instant parseUtcRestoreTime(String value) {
        if (blank(value) || !value.endsWith("Z")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RESTORE_TIME", false,
                    "restoreTime must be an ISO-8601 UTC timestamp ending in Z.");
        }
        try {
            return Instant.parse(value);
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RESTORE_TIME", false,
                    "restoreTime must be an ISO-8601 UTC timestamp.");
        }
    }

    private RestoreResponse duplicateForSource(String project, String databaseId,
                                               String idempotencyKey, String requestHash) {
        RestoreRequestMetadata duplicate = restoreRepository
                .findByProjectNameAndSourceDatabaseIdAndIdempotencyKey(project, databaseId, idempotencyKey)
                .orElse(null);
        return duplicate == null ? null : duplicateResponse(duplicate, requestHash);
    }

    private RestoreResponse duplicateResponse(RestoreRequestMetadata restore, String requestHash) {
        if (!requestHash.equals(restore.getRequestHash())) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", false,
                    "This Idempotency-Key was already used with a different restore request.");
        }
        return response(restore);
    }

    public RestoreResponse response(RestoreRequestMetadata restore) {
        return new RestoreResponse(restore.getRestoreId(), restore.getRestoredDatabaseId(),
                restore.getRestoreMode(), restore.getSourceBackupId(), restore.getRestoreTime(), restore.getStatus(),
                restore.getCreatedAt(), restore.getStartedAt(), restore.getCompletedAt(),
                restore.getFailureCode(), restore.getFailureMessage());
    }

    private RestoreResponse refreshAndRespond(RestoreRequestMetadata restore) {
        try {
            restoreReconciler.refresh(restore);
        } catch (Exception exception) {
            if (!BackupRestoreSafety.retryable(exception)) throw exception;
        }
        return response(restoreRepository.findById(restore.getRestoreId()).orElse(restore));
    }

    private void validateIdempotencyKey(String value) {
        if (value == null || !IDEMPOTENCY_KEY.matcher(value).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY", false,
                    "Idempotency-Key must be 8-128 characters using letters, numbers, '.', '_', ':' or '-'.");
        }
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
