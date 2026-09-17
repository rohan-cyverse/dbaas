package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.dto.CreateRestoreRequest;
import com.cyfuture.dbaas.dto.RestoreResponse;
import com.cyfuture.dbaas.entity.BackupMetadata;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.entity.OperationMetadata;
import com.cyfuture.dbaas.entity.RestoreRequestMetadata;
import com.cyfuture.dbaas.exception.ApiException;
import com.cyfuture.dbaas.model.BackupStatus;
import com.cyfuture.dbaas.model.BackupType;
import com.cyfuture.dbaas.model.DatabaseStatus;
import com.cyfuture.dbaas.model.OperationStatus;
import com.cyfuture.dbaas.model.OperationType;
import com.cyfuture.dbaas.model.ProvisioningStage;
import com.cyfuture.dbaas.model.RestoreAccessMode;
import com.cyfuture.dbaas.model.RestoreMode;
import com.cyfuture.dbaas.model.RestoreStatus;
import com.cyfuture.dbaas.repository.BackupMetadataRepository;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import com.cyfuture.dbaas.repository.OperationMetadataRepository;
import com.cyfuture.dbaas.repository.RestoreRequestMetadataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Value;
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
    private final DatabaseService databaseService;
    private final OperationService operationService;

    @Value("${dbaas.restore.rollback-retention-minutes:60}")
    private long rollbackRetentionMinutes = 60L;

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
            return restoreFull(project, source, idempotencyKey, request);
        }
        if (request.mode() == RestoreMode.POINT_IN_TIME) {
            if (!blank(request.backupId()) || blank(request.restoreTime())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RESTORE_MODE", false,
                        "POINT_IN_TIME restore requires restoreTime and does not accept backupId.");
            }
            return restorePointInTime(project, source, idempotencyKey, request);
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

    public RestoreResponse active(String project, String databaseId) {
        projectService.requireActiveProject(project);
        return restoreRepository
                .findFirstByProjectNameAndSourceDatabaseIdAndTemporaryTrueAndPromotedAtIsNullAndDeletedAtIsNullAndStatusInOrderByCreatedAtDesc(
                        project, databaseId, activeTemporaryStatuses())
                .map(this::refreshAndRespond)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ACTIVE_RESTORE_NOT_FOUND", false,
                        "No active temporary restore was found for this database."));
    }

    @Transactional
    public RestoreResponse promote(String project, String databaseId, String restoreId) {
        projectService.requireActiveProject(project);
        RestoreRequestMetadata restore = restoreRepository
                .findByRestoreIdAndProjectNameAndSourceDatabaseId(restoreId, project, databaseId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RESTORE_NOT_FOUND", false,
                        "Restore was not found for this database."));
        if (!restore.isTemporary() || restore.getPromotedAt() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "RESTORE_NOT_TEMPORARY", false,
                    "Only an active temporary restore can be promoted.");
        }
        if (restore.getStatus() != RestoreStatus.READY && restore.getStatus() != RestoreStatus.COMPLETED) {
            throw new ApiException(HttpStatus.CONFLICT, "RESTORE_NOT_READY", true,
                    "Restore must be READY before it can be promoted.");
        }
        restore.setTemporary(false);
        restore.setExpiresAfterHours(null);
        restore.setExpiresAt(null);
        restore.setPromotedAt(Instant.now());
        restoreRepository.save(restore);
        return response(restore);
    }

    @Transactional
    public RestoreResponse deleteTemporary(String project, String databaseId, String restoreId) {
        projectService.requireActiveProject(project);
        RestoreRequestMetadata restore = restoreRepository
                .findByRestoreIdAndProjectNameAndSourceDatabaseId(restoreId, project, databaseId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RESTORE_NOT_FOUND", false,
                        "Restore was not found for this database."));
        if (!restore.isTemporary() || restore.getPromotedAt() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "RESTORE_NOT_TEMPORARY", false,
                    "Only a temporary restore can be deleted through this endpoint.");
        }
        if (restore.getDeletedAt() != null) return response(restore);
        restore.setStatus(RestoreStatus.DELETING);
        restore.setDeletedAt(Instant.now());
        restoreRepository.save(restore);
        submitAfterCommit(() -> databaseService.delete(project, restore.getRestoredDatabaseId()));
        return response(restore);
    }

    private RestoreResponse restoreFull(String project, DatabaseMetadata source,
                                        String idempotencyKey, CreateRestoreRequest request) {
        RestoreOptions options = validateRestoreOptions(request, source);
        String requestHash = hash(RestoreMode.FULL.name(), request.backupId(), "IN_PLACE",
                String.valueOf(request.createSafetyBackup()));
        RestoreResponse duplicate = duplicateForSource(project, source.getDatabaseId(), idempotencyKey, requestHash);
        if (duplicate != null) return duplicate;
        BackupMetadata backup = backupRepository.findByBackupIdAndProjectNameAndDatabaseId(
                        request.backupId(), project, source.getDatabaseId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "BACKUP_NOT_FOUND", false,
                        "Backup was not found for this database."));
        validateFullBackup(backup, source);
        return createRestore(project, source, idempotencyKey, backup, RestoreMode.FULL, null, requestHash, options);
    }

    private RestoreResponse restorePointInTime(String project, DatabaseMetadata source,
                                               String idempotencyKey, CreateRestoreRequest request) {
        RestoreOptions options = validateRestoreOptions(request, source);
        BackupEngineStrategy strategy = strategies.require(source.getEngine());
        if (!strategy.supportsPitrTopology(source.getMode())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PITR_NOT_SUPPORTED", false,
                    "Point-in-time recovery is not supported for this database topology.");
        }
        Instant restoreTime = parseUtcRestoreTime(request.restoreTime());
        if (!restoreTime.isBefore(Instant.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RESTORE_TIME", false,
                    "restoreTime must be a past UTC timestamp.");
        }
        String requestHash = hash(RestoreMode.POINT_IN_TIME.name(), restoreTime.toString(), "IN_PLACE",
                String.valueOf(request.createSafetyBackup()));
        RestoreResponse duplicate = duplicateForSource(project, source.getDatabaseId(), idempotencyKey, requestHash);
        if (duplicate != null) return duplicate;
        backupPolicyService.refresh(project, source.getDatabaseId());
        PitrRecoveryService.PitrWindow window = pitrRecoveryService.requireRestoreWindow(project, source.getDatabaseId());
        if (!restoreTime.isAfter(window.startsAt()) || !restoreTime.isBefore(window.endsAt())) {
            throw new ApiException(HttpStatus.CONFLICT, "RESTORE_TIME_OUTSIDE_WINDOW", false,
                    "restoreTime must be inside the currently recoverable window, not exactly at its boundaries.");
        }
        // KubeBlocks PITR restores from the continuous backup resource. That
        // resource references the qualifying full backup used as its base.
        return createRestore(project, source, idempotencyKey, window.latestContinuousBackup(),
                RestoreMode.POINT_IN_TIME, restoreTime, requestHash, options);
    }

    private RestoreResponse createRestore(String project, DatabaseMetadata source, String idempotencyKey,
                                          BackupMetadata backup, RestoreMode mode, Instant restoreTime,
                                          String requestHash, RestoreOptions options) {
        projectService.requireActiveProject(project);
        operationService.rejectIfMutatingOperationActive(project, source.getDatabaseId());
        ensureNoActiveTemporaryRestore(project, source.getDatabaseId());
        String restoreId = "rst-" + shortId();
        String operationId = "op-" + shortId();
        String restoreSuffix = shortId().substring(0, 8);
        String temporaryClusterName = source.getDatabaseId() + "-restore-" + restoreSuffix;
        String oldClusterName = source.physicalClusterName();
        Instant now = Instant.now();
        if (source.getActiveClusterName() == null || source.getActiveClusterName().isBlank()) {
            source.setActiveClusterName(source.getDatabaseId());
        }
        source.setProvisioningStage(ProvisioningStage.CREATING_SAFETY_BACKUP);
        source.setProgress(5);
        source.setMessage("Restore request queued");
        source.setUpdatedAt(now);

        RestoreRequestMetadata restore = new RestoreRequestMetadata();
        restore.setRestoreId(restoreId);
        restore.setOperationId(operationId);
        restore.setProjectName(project);
        restore.setSourceDatabaseId(source.getDatabaseId());
        restore.setSourceBackupId(backup.getBackupId());
        restore.setRestoreMode(mode);
        restore.setRestoredDatabaseId(source.getDatabaseId());
        restore.setTemporaryClusterName(temporaryClusterName);
        restore.setOldClusterName(oldClusterName);
        restore.setTargetDatabaseName(source.getDisplayName());
        restore.setRestoreTime(restoreTime);
        restore.setTemporary(true);
        restore.setExpiresAfterHours(null);
        restore.setExpiresAt(null);
        restore.setAccessMode(options.accessMode());
        restore.setKubernetesOpsRequestName(restoreId);
        restore.setStatus(RestoreStatus.PENDING);
        restore.setIdempotencyKey(idempotencyKey);
        restore.setRequestHash(requestHash);
        restore.setCreatedAt(now);

        OperationMetadata operation = OperationMetadata.builder()
                .operationId(operationId)
                .databaseId(source.getDatabaseId())
                .projectName(project)
                .type(OperationType.RESTORE)
                .status(OperationStatus.PENDING)
                .provisioningStage(ProvisioningStage.CREATING_SAFETY_BACKUP)
                .progress(5)
                .message(mode == RestoreMode.POINT_IN_TIME ? "Point-in-time restore queued" : "Restore queued")
                .idempotencyKey(idempotencyKey)
                .requestHash(requestHash)
                .opsRequestName(restore.getKubernetesOpsRequestName())
                .createdAt(now)
                .timeoutAt(now.plusMillis(3_600_000L))
                .build();
        try {
            databaseRepository.save(source);
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
                restore.getOperationId(), restore.getRestoreMode(), restore.getSourceBackupId(), restore.getRestoreTime(),
                restore.getTargetDatabaseName(), restore.isTemporary(), restore.getExpiresAt(),
                restore.getAccessMode(), restore.getStatus(),
                restore.getCreatedAt(), restore.getStartedAt(), restore.getCompletedAt(),
                restore.getPromotedAt(), restore.getDeletedAt(),
                restore.getFailureCode(), restore.getFailureMessage());
    }

    public void expireTemporaryRestore(RestoreRequestMetadata restore) {
        if (!restore.isTemporary() || restore.getPromotedAt() != null || restore.getDeletedAt() != null) return;
        restore.setStatus(RestoreStatus.EXPIRED);
        restore.setDeletedAt(Instant.now());
        restoreRepository.save(restore);
        databaseService.delete(restore.getProjectName(), restore.getRestoredDatabaseId());
    }

    private RestoreOptions validateRestoreOptions(CreateRestoreRequest request, DatabaseMetadata source) {
        if (request.target() == null || !"IN_PLACE".equals(request.target())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_RESTORE_TARGET", false,
                    "Restore target must be IN_PLACE.");
        }
        if (Boolean.FALSE.equals(request.createSafetyBackup())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SAFETY_BACKUP_REQUIRED", false,
                    "In-place restore requires createSafetyBackup=true.");
        }
        if (blank(request.confirmation()) || !source.getDisplayName().equals(request.confirmation())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RESTORE_CONFIRMATION_REQUIRED", false,
                    "confirmation must match the database name.");
        }
        return new RestoreOptions(null, false, 0, RestoreAccessMode.PRIVATE);
    }

    private void ensureNoActiveTemporaryRestore(String project, String databaseId) {
        restoreRepository
                .findFirstByProjectNameAndSourceDatabaseIdAndTemporaryTrueAndPromotedAtIsNullAndDeletedAtIsNullAndStatusInOrderByCreatedAtDesc(
                        project, databaseId, activeTemporaryStatuses())
                .ifPresent(active -> {
                    throw new ApiException(HttpStatus.CONFLICT, "ACTIVE_RESTORE_EXISTS", true,
                            "Restore " + active.getRestoreId() + " is already active for this database.");
                });
    }

    private List<RestoreStatus> activeTemporaryStatuses() {
        return List.of(RestoreStatus.PENDING, RestoreStatus.SAFETY_BACKUP, RestoreStatus.RESTORING,
                RestoreStatus.VALIDATING, RestoreStatus.CUTTING_OVER, RestoreStatus.ROLLING_BACK,
                RestoreStatus.RUNNING, RestoreStatus.READY);
    }

    private record RestoreOptions(String targetDatabaseName, boolean temporary,
                                  int expiresAfterHours, RestoreAccessMode accessMode) {}

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
