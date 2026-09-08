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
import com.cyfuture.dbaas.model.DatabaseStatus;
import com.cyfuture.dbaas.model.DesiredState;
import com.cyfuture.dbaas.model.OperationStatus;
import com.cyfuture.dbaas.model.OperationType;
import com.cyfuture.dbaas.model.ProvisioningStage;
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

/** Creates a new KubeBlocks Restore OpsRequest; it never provisions an empty replacement Cluster. */
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

    @Transactional
    public AcceptedOperationResponse restore(String project, String databaseId, String backupId,
                                             String idempotencyKey, CreateRestoreRequest request) {
        ProjectMetadata projectMetadata = projectService.requireActiveProject(project);
        validateIdempotencyKey(idempotencyKey);
        BackupMetadata backup = backupRepository.findByBackupIdAndProjectNameAndDatabaseId(
                        backupId, project, databaseId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "BACKUP_NOT_FOUND", false,
                        "Backup was not found for this database."));
        if (backup.getStatus() != BackupStatus.COMPLETED) {
            throw new ApiException(HttpStatus.CONFLICT, "BACKUP_NOT_AVAILABLE", false,
                    "Only an available completed backup can be restored.");
        }
        if (backup.getKubernetesBackupName() == null || backup.getKubernetesBackupName().isBlank()) {
            throw new ApiException(HttpStatus.CONFLICT, "BACKUP_NOT_AVAILABLE", false,
                    "The KubeBlocks backup identity is unavailable for restore.");
        }
        if (request != null && request.restoreTime() != null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "FEATURE_NOT_AVAILABLE", false,
                    "Point-in-time restore is not available yet.");
        }
        BackupEngineStrategy strategy = strategies.require(backup.getEngine());
        String backupMethod = backup.getBackupMethod() == null || backup.getBackupMethod().isBlank()
                ? strategy.manualFullMethod() : backup.getBackupMethod();
        if (backup.getBackupType() != com.cyfuture.dbaas.model.BackupType.FULL
                || !strategy.manualFullMethod().equals(backupMethod)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "FEATURE_NOT_AVAILABLE", false,
                    "Only full backups created with the supported engine method can be restored.");
        }
        if (!strategy.supportsTopology(backup.getSourceMode())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "BACKUP_TOPOLOGY_UNSUPPORTED", false,
                    "This backup was created from a topology not supported for restore.");
        }
        String requestedName = request == null ? null : request.name();
        String requestHash = hash(blank(requestedName) ? "" : requestedName, "");
        RestoreRequestMetadata duplicate = restoreRepository
                .findByProjectNameAndSourceBackupIdAndIdempotencyKey(project, backupId, idempotencyKey)
                .orElse(null);
        if (duplicate != null) return duplicateResponse(duplicate, requestHash);

        String restoredDatabaseId = "db-" + shortId();
        String restoreId = "rst-" + shortId();
        String operationId = "op-" + shortId();
        Instant now = Instant.now();
        String restoredDisplayName = uniqueDisplayName(project, requestedName, backup);
        DatabaseMetadata target = restoredDatabase(backup, project, restoredDatabaseId, restoredDisplayName);
        target.setOperationId(operationId);
        // This internal key avoids colliding with user supplied create keys while
        // RestoreRequestMetadata remains the external idempotency authority.
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
        restore.setSourceBackupId(backupId);
        restore.setSourceKubernetesBackupName(backup.getKubernetesBackupName());
        restore.setSourceBackupNamespace(backup.getKubernetesNamespace());
        restore.setRestoredDatabaseId(restoredDatabaseId);
        // The resource display name is user-facing metadata. The connection
        // must instead target the application database restored from backup.
        restore.setRestoredDatabaseName(logicalDatabaseName(backup));
        restore.setEngine(backup.getEngine());
        restore.setRestoreTime(null);
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
                .message("Restore request queued")
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
                    .findByProjectNameAndSourceBackupIdAndIdempotencyKey(project, backupId, idempotencyKey)
                    .orElseThrow(() -> exception);
            return duplicateResponse(existing, requestHash);
        }
        submitAfterCommit(() -> submissionService.submit(restoreId));
        return accepted(restore, OperationStatus.PENDING);
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
        // A restore can only be deleted after its restore operation is terminal;
        // normal deletion protection remains opt-in after recovery.
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
        // Historic backup rows predate the safe logical-name snapshot. All
        // DBaaS-created clusters use this deterministic naming convention.
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
