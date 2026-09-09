package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.dto.AcceptedOperationResponse;
import com.cyfuture.dbaas.dto.CreateRestoreRequest;
import com.cyfuture.dbaas.entity.BackupMetadata;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.entity.OperationMetadata;
import com.cyfuture.dbaas.entity.ProjectMetadata;
import com.cyfuture.dbaas.entity.RestoreRequestMetadata;
import com.cyfuture.dbaas.exception.ApiException;
import com.cyfuture.dbaas.model.BackupStatus;
import com.cyfuture.dbaas.model.BackupType;
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
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.UUID;
import java.util.regex.Pattern;

/** Creates a new KubeBlocks Restore OpsRequest; it never replaces the source Cluster. */
@Service
public class RestoreService {
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9._:-]{8,128}$");

    private final BackupMetadataRepository backupRepository;
    private final RestoreRequestMetadataRepository restoreRepository;
    private final DatabaseMetadataRepository databaseRepository;
    private final OperationMetadataRepository operationRepository;
    private final ProjectService projectService;
    private final BackupEngineStrategies strategies;
    private final RestoreSubmissionService submissionService;
    private final PitrRecoveryService pitrRecoveryService;

    @Autowired
    public RestoreService(BackupMetadataRepository backupRepository,
                          RestoreRequestMetadataRepository restoreRepository,
                          DatabaseMetadataRepository databaseRepository,
                          OperationMetadataRepository operationRepository,
                          ProjectService projectService,
                          BackupEngineStrategies strategies,
                          RestoreSubmissionService submissionService,
                          PitrRecoveryService pitrRecoveryService) {
        this.backupRepository = backupRepository;
        this.restoreRepository = restoreRepository;
        this.databaseRepository = databaseRepository;
        this.operationRepository = operationRepository;
        this.projectService = projectService;
        this.strategies = strategies;
        this.submissionService = submissionService;
        this.pitrRecoveryService = pitrRecoveryService;
    }

    /** Compatibility constructor for the existing backup-specific restore API. */
    RestoreService(BackupMetadataRepository backupRepository,
                   RestoreRequestMetadataRepository restoreRepository,
                   DatabaseMetadataRepository databaseRepository,
                   OperationMetadataRepository operationRepository,
                   ProjectService projectService,
                   BackupEngineStrategies strategies,
                   RestoreSubmissionService submissionService) {
        this(backupRepository, restoreRepository, databaseRepository, operationRepository, projectService,
                strategies, submissionService, null);
    }

    /**
     * Existing /backups/{backupId}/restore endpoint. It remains a full restore
     * endpoint so old clients keep their exact semantics.
     */
    @Transactional
    public AcceptedOperationResponse restore(String project, String databaseId, String backupId,
                                             String idempotencyKey, CreateRestoreRequest request) {
        if (request != null && request.restoreMode() == RestoreMode.POINT_IN_TIME) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RESTORE_MODE", false,
                    "Use the database restores endpoint for point-in-time recovery.");
        }
        if (request != null && !blank(request.backupId()) && !backupId.equals(request.backupId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RESTORE_MODE", false,
                    "backupId in the request body must match the backup path parameter.");
        }
        if (request != null && !blank(request.restoreTime())) {
            // This was previously rejected; retaining the behavior keeps the
            // backup-specific full-restore endpoint backward compatible.
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "FEATURE_NOT_AVAILABLE", false,
                    "Use the database restores endpoint for point-in-time recovery.");
        }
        return restoreFull(project, databaseId, backupId, idempotencyKey, request, true);
    }

    /** New database-scoped restore endpoint supporting FULL and POINT_IN_TIME modes. */
    @Transactional
    public AcceptedOperationResponse restore(String project, String databaseId,
                                             String idempotencyKey, CreateRestoreRequest request) {
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RESTORE_MODE", false,
                    "restoreMode and its required fields must be supplied.");
        }
        RestoreMode mode = request.restoreMode();
        if (mode == null) {
            mode = !blank(request.backupId()) ? RestoreMode.FULL
                    : !blank(request.restoreTime()) ? RestoreMode.POINT_IN_TIME : null;
        }
        if (mode == RestoreMode.FULL) {
            if (blank(request.backupId()) || !blank(request.restoreTime())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RESTORE_MODE", false,
                        "FULL restore requires backupId and does not accept restoreTime.");
            }
            return restoreFull(project, databaseId, request.backupId(), idempotencyKey, request, false);
        }
        if (mode == RestoreMode.POINT_IN_TIME) {
            if (!blank(request.backupId()) || blank(request.restoreTime())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RESTORE_MODE", false,
                        "POINT_IN_TIME restore requires restoreTime and does not accept backupId.");
            }
            return restorePointInTime(project, databaseId, idempotencyKey, request);
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RESTORE_MODE", false,
                "restoreMode must be FULL or POINT_IN_TIME.");
    }

    private AcceptedOperationResponse restoreFull(String project, String databaseId, String backupId,
                                                  String idempotencyKey, CreateRestoreRequest request,
                                                  boolean legacyRequestHash) {
        ProjectMetadata projectMetadata = projectService.requireActiveProject(project);
        validateIdempotencyKey(idempotencyKey);
        String requestedName = request == null ? null : request.name();
        String requestHash = legacyRequestHash
                ? hash(blank(requestedName) ? "" : requestedName, "")
                : hash(RestoreMode.FULL.name(), blank(requestedName) ? "" : requestedName, "", backupId);
        AcceptedOperationResponse duplicate = duplicateForSource(project, databaseId, idempotencyKey, requestHash);
        if (duplicate != null) return duplicate;
        BackupMetadata backup = backupRepository.findByBackupIdAndProjectNameAndDatabaseId(
                        backupId, project, databaseId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "BACKUP_NOT_FOUND", false,
                        "Backup was not found for this database."));
        validateFullBackup(backup);
        return createRestore(projectMetadata, project, databaseId, idempotencyKey, request, backup,
                RestoreMode.FULL, null, null, requestHash);
    }

    private AcceptedOperationResponse restorePointInTime(String project, String databaseId,
                                                         String idempotencyKey, CreateRestoreRequest request) {
        projectService.requireActiveProject(project);
        validateIdempotencyKey(idempotencyKey);
        DatabaseMetadata source = databaseRepository.findByDatabaseIdAndProjectNameForUpdate(databaseId, project)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "DATABASE_NOT_FOUND", false,
                        "Database was not found in this project."));
        BackupEngineStrategy strategy = strategies.require(source.getEngine());
        if (!strategy.supportsPitrTopology(source.getMode())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PITR_NOT_SUPPORTED", false,
                    "Point-in-time recovery is not supported for this database topology.");
        }
        Instant restoreTime = parseUtcRestoreTime(request.restoreTime());
        String requestHash = hash(RestoreMode.POINT_IN_TIME.name(),
                blank(request.name()) ? "" : request.name(), restoreTime.toString());
        AcceptedOperationResponse duplicate = duplicateForSource(project, databaseId, idempotencyKey, requestHash);
        if (duplicate != null) return duplicate;
        if (!restoreTime.isBefore(Instant.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RESTORE_TIME", false,
                    "restoreTime must be a past UTC timestamp.");
        }
        if (pitrRecoveryService == null) {
            throw new ApiException(HttpStatus.CONFLICT, "PITR_WINDOW_UNAVAILABLE", true,
                    "Point-in-time recovery metadata is still being prepared.");
        }
        PitrRecoveryService.PitrWindow window = pitrRecoveryService.requireRestoreWindow(project, databaseId);
        if (restoreTime.isBefore(window.recoverableFrom()) || restoreTime.isAfter(window.recoverableUntil())) {
            throw new ApiException(HttpStatus.CONFLICT, "RESTORE_TIME_OUTSIDE_WINDOW", false,
                    "restoreTime is outside the currently recoverable PITR window.");
        }
        ProjectMetadata projectMetadata = projectService.requireActiveProject(project);
        return createRestore(projectMetadata, project, databaseId, idempotencyKey, request, window.baseBackup(),
                RestoreMode.POINT_IN_TIME, restoreTime, window.latestContinuousBackup(), requestHash);
    }

    private AcceptedOperationResponse createRestore(ProjectMetadata projectMetadata, String project,
                                                    String databaseId, String idempotencyKey,
                                                    CreateRestoreRequest request, BackupMetadata backup,
                                                    RestoreMode mode, Instant restoreTime,
                                                    BackupMetadata continuousBackup, String requestHash) {
        String requestedName = request == null ? null : request.name();

        String restoredDatabaseId = "db-" + shortId();
        String restoreId = "rst-" + shortId();
        String operationId = "op-" + shortId();
        Instant now = Instant.now();
        String restoredDisplayName = uniqueDisplayName(project, requestedName, backup);
        DatabaseMetadata target = restoredDatabase(backup, project, restoredDatabaseId, restoredDisplayName);
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
        restore.setSourceDatabaseId(databaseId);
        restore.setSourceBackupId(backup.getBackupId());
        restore.setRestoreMode(mode);
        restore.setContinuousBackupId(continuousBackup == null ? null : continuousBackup.getBackupId());
        restore.setSourceKubernetesBackupName(backup.getKubernetesBackupName());
        restore.setSourceBackupNamespace(backup.getKubernetesNamespace());
        restore.setRestoredDatabaseId(restoredDatabaseId);
        restore.setRestoredDatabaseName(logicalDatabaseName(backup));
        restore.setEngine(backup.getEngine());
        restore.setRestoreTime(restoreTime);
        restore.setKubernetesOpsRequestName(restoreId);
        restore.setKubernetesClusterName(restoredDatabaseId);
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
                .message(mode == RestoreMode.POINT_IN_TIME ? "Point-in-time restore request queued"
                        : "Restore request queued")
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
                    .findByProjectNameAndSourceDatabaseIdAndIdempotencyKey(project, databaseId, idempotencyKey)
                    .orElseThrow(() -> exception);
            return duplicateResponse(existing, requestHash);
        }
        submitAfterCommit(() -> submissionService.submit(restoreId));
        return accepted(restore, OperationStatus.PENDING);
    }

    private void validateFullBackup(BackupMetadata backup) {
        if (backup.getStatus() != BackupStatus.COMPLETED) {
            throw new ApiException(HttpStatus.CONFLICT, "BACKUP_NOT_AVAILABLE", false,
                    "Only an available completed backup can be restored.");
        }
        if (blank(backup.getKubernetesBackupName())) {
            throw new ApiException(HttpStatus.CONFLICT, "BACKUP_NOT_AVAILABLE", false,
                    "The KubeBlocks backup identity is unavailable for restore.");
        }
        BackupEngineStrategy strategy = strategies.require(backup.getEngine());
        String backupMethod = blank(backup.getBackupMethod())
                ? strategy.manualFullMethod() : backup.getBackupMethod();
        if (backup.getBackupType() != BackupType.FULL || !strategy.manualFullMethod().equals(backupMethod)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "FEATURE_NOT_AVAILABLE", false,
                    "Only completed full backups created with the supported engine method can be restored.");
        }
        if (!strategy.supportsTopology(backup.getSourceMode())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "BACKUP_TOPOLOGY_UNSUPPORTED", false,
                    "This backup was created from a topology not supported for restore.");
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

    private DatabaseMetadata restoredDatabase(BackupMetadata backup, String project,
                                               String databaseId, String displayName) {
        DatabaseMetadata target = new DatabaseMetadata();
        target.setDatabaseId(databaseId);
        target.setProjectName(project);
        target.setDisplayName(displayName);
        target.setRemark("Restored from " + backup.getBackupId());
        target.setEngine(backup.getEngine());
        target.setMode(backup.getSourceMode());
        target.setDatabaseVersion(backup.getSourceDatabaseVersion());
        target.setSizePlan(backup.getSourceSizePlan());
        target.setStorageGi(backup.getSourceStorageGi());
        target.setReplicas(backup.getSourceReplicas());
        target.setShards(backup.getSourceShards());
        target.setExpectedReplicas(backup.getSourceMode() == com.cyfuture.dbaas.model.DatabaseMode.SHARDING
                ? backup.getSourceShards() * backup.getSourceReplicas() + 5
                : backup.getSourceReplicas());
        target.setObservedReadyReplicas(0);
        target.setObservedServiceReady(false);
        target.setTimezone(backup.getSourceTimezone());
        target.setAllowedCidrs(backup.getSourceAllowedCidrs());
        target.setTags(backup.getSourceTags());
        target.setDeletionProtection(false);
        return target;
    }

    private String uniqueDisplayName(String project, String requestedName, BackupMetadata backup) {
        String base = blank(requestedName) ? "restore-" + backup.getBackupId().substring(4) : requestedName.trim();
        base = base.substring(0, Math.min(32, base.length()));
        if (!databaseRepository.existsByProjectNameAndDisplayName(project, base)) return base;
        for (int i = 0; i < 12; i++) {
            String suffix = "-" + shortId().substring(0, 4);
            String candidate = base.substring(0, Math.min(base.length(), 32 - suffix.length())) + suffix;
            if (!databaseRepository.existsByProjectNameAndDisplayName(project, candidate)) return candidate;
        }
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_NAME_ALLOCATION_FAILED", true,
                "Unable to allocate a name for the restored database. Retry the request.");
    }

    private String logicalDatabaseName(BackupMetadata backup) {
        if (backup.getSourceLogicalDatabaseName() != null
                && !backup.getSourceLogicalDatabaseName().isBlank()) {
            return backup.getSourceLogicalDatabaseName();
        }
        return CredentialLifecycleService.managedDatabaseName(backup.getDatabaseId());
    }

    private AcceptedOperationResponse duplicateResponse(RestoreRequestMetadata restore, String requestHash) {
        if (!requestHash.equals(restore.getRequestHash())) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", false,
                    "This Idempotency-Key was already used with a different restore request.");
        }
        OperationStatus status = operationRepository.findById(restore.getOperationId())
                .map(OperationMetadata::getStatus).orElse(OperationStatus.PENDING);
        return accepted(restore, status);
    }

    private AcceptedOperationResponse duplicateForSource(String project, String databaseId,
                                                         String idempotencyKey, String requestHash) {
        RestoreRequestMetadata duplicate = restoreRepository
                .findByProjectNameAndSourceDatabaseIdAndIdempotencyKey(project, databaseId, idempotencyKey)
                .orElse(null);
        return duplicate == null ? null : duplicateResponse(duplicate, requestHash);
    }

    private AcceptedOperationResponse accepted(RestoreRequestMetadata restore, OperationStatus status) {
        return new AcceptedOperationResponse(restore.getOperationId(), restore.getRestoredDatabaseId(), status,
                "/api/v1/operations/" + restore.getOperationId(), 5);
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
