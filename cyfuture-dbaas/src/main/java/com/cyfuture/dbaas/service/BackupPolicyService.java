package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.dto.AcceptedOperationResponse;
import com.cyfuture.dbaas.dto.BackupConfigurationRequest;
import com.cyfuture.dbaas.dto.BackupPolicyResponse;
import com.cyfuture.dbaas.entity.BackupPolicyMetadata;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.entity.OperationMetadata;
import com.cyfuture.dbaas.exception.ApiException;
import com.cyfuture.dbaas.model.BackupPolicyStatus;
import com.cyfuture.dbaas.model.OperationStatus;
import com.cyfuture.dbaas.model.OperationType;
import com.cyfuture.dbaas.model.PitrStatus;
import com.cyfuture.dbaas.model.ProvisioningStage;
import com.cyfuture.dbaas.repository.BackupPolicyMetadataRepository;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import com.cyfuture.dbaas.repository.OperationMetadataRepository;
import lombok.RequiredArgsConstructor;
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

/** Desired-state API for KubeBlocks-generated BackupPolicy and BackupSchedule resources. */
@Service
@RequiredArgsConstructor
public class BackupPolicyService {
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9._:-]{8,128}$");

    private final BackupPolicyMetadataRepository policyRepository;
    private final DatabaseMetadataRepository databaseRepository;
    private final OperationMetadataRepository operationRepository;
    private final ProjectService projectService;
    private final BackupConfigurationNormalizer normalizer;
    private final BackupEngineStrategies strategies;
    private final KubeBlocksClient kubeBlocksClient;
    private final BackupPolicySubmissionService submissionService;
    private final PitrRecoveryService pitrRecoveryService;

    @Transactional
    public AcceptedOperationResponse update(String project, String databaseId, String idempotencyKey,
                                            BackupConfigurationRequest request) {
        projectService.requireActiveProject(project);
        validateIdempotencyKey(idempotencyKey);
        DatabaseMetadata database = databaseRepository.findByDatabaseIdAndProjectNameForUpdate(databaseId, project)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "DATABASE_NOT_FOUND", false,
                        "Database was not found in this project."));
        if (database.getStatus() == com.cyfuture.dbaas.model.DatabaseStatus.DELETING
                || database.getStatus() == com.cyfuture.dbaas.model.DatabaseStatus.DELETED) {
            throw new ApiException(HttpStatus.CONFLICT, "DATABASE_DELETION_IN_PROGRESS", false,
                    "Backup policy cannot be changed while the database is being deleted.");
        }
        BackupEngineStrategy strategy = strategies.require(database.getEngine());
        if (!strategy.supportsTopology(database.getMode())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "BACKUP_TOPOLOGY_UNSUPPORTED", false,
                    "The installed KubeBlocks backup template does not support this database topology.");
        }
        BackupConfigurationNormalizer.NormalizedBackupConfiguration desired = normalizer.normalize(request);
        kubeBlocksClient.validateReadyBackupRepository(desired.repository());
        validatePitrSupport(database, strategy, desired);
        String requestHash = hash(desired.repository(), String.valueOf(desired.autoBackupEnabled()),
                String.valueOf(desired.retentionDays()), String.valueOf(desired.cronExpression()),
                desired.timezone(), desired.retentionPolicy().name(), String.valueOf(desired.pitrEnabled()));

        BackupPolicyMetadata policy = policyRepository.findByProjectNameAndDatabaseId(project, databaseId)
                .orElse(null);
        if (policy != null && idempotencyKey.equals(policy.getIdempotencyKey())) {
            if (!requestHash.equals(policy.getRequestHash())) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", false,
                        "This Idempotency-Key was already used with a different backup policy request.");
            }
            return accepted(policy.getPolicyUpdateOperationId(), policy.getPolicyId());
        }
        if (policy != null && policy.getPolicyUpdateOperationId() != null
                && operationStillActive(policy.getPolicyUpdateOperationId())) {
            throw new ApiException(HttpStatus.CONFLICT, "BACKUP_POLICY_UPDATE_IN_PROGRESS", false,
                    "A backup policy update is already in progress.");
        }

        Instant now = Instant.now();
        if (policy == null) {
            policy = new BackupPolicyMetadata();
            policy.setPolicyId("bpol-" + shortId());
            policy.setProjectName(project);
            policy.setDatabaseId(databaseId);
            policy.setCreatedAt(now);
        }
        apply(policy, database, desired, "op-" + shortId(), idempotencyKey, requestHash, now);
        String operationId = policy.getPolicyUpdateOperationId();
        policyRepository.save(policy);
        operationRepository.save(OperationMetadata.builder()
                .operationId(operationId)
                .databaseId(databaseId)
                .projectName(project)
                .type(OperationType.BACKUP_POLICY_UPDATE)
                .status(OperationStatus.PENDING)
                .provisioningStage(ProvisioningStage.QUEUED)
                .progress(0)
                .message("Backup policy update queued")
                .idempotencyKey(idempotencyKey)
                .requestHash(requestHash)
                .createdAt(now)
                .build());
        String policyId = policy.getPolicyId();
        submitAfterCommit(() -> submissionService.submit(policyId));
        return new AcceptedOperationResponse(operationId, policyId, OperationStatus.PENDING,
                "/api/v1/operations/" + operationId, 5);
    }

    public BackupPolicyResponse get(String project, String databaseId) {
        projectService.requireActiveProject(project);
        BackupPolicyMetadata policy = policyRepository.findByProjectNameAndDatabaseId(project, databaseId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "BACKUP_POLICY_NOT_CONFIGURED", false,
                        "No backup policy has been configured for this database."));
        pitrRecoveryService.refresh(policy);
        return response(policy);
    }

    /** Returns the canonical UTC form stored in Cluster.spec.backup during database creation. */
    public BackupConfigurationRequest normalizeForCreation(BackupConfigurationRequest request) {
        BackupConfigurationNormalizer.NormalizedBackupConfiguration desired = normalizer.normalize(request);
        return new BackupConfigurationRequest(desired.repository(), desired.autoBackupEnabled(),
                desired.retentionDays(), desired.cronExpression(), desired.timezone(),
                desired.retentionPolicy(), desired.pitrEnabled());
    }

    /** Builds a pending desired-policy row to be saved atomically with database creation metadata. */
    public BackupPolicyMetadata initialPolicy(DatabaseMetadata database,
                                              BackupConfigurationRequest request,
                                              String databaseOperationId) {
        BackupConfigurationNormalizer.NormalizedBackupConfiguration desired = normalizer.normalize(request);
        kubeBlocksClient.validateReadyBackupRepository(desired.repository());
        BackupEngineStrategy strategy = strategies.require(database.getEngine());
        if (!strategy.supportsTopology(database.getMode())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "BACKUP_TOPOLOGY_UNSUPPORTED", false,
                    "The installed KubeBlocks backup template does not support this database topology.");
        }
        validatePitrSupport(database, strategy, desired);
        BackupPolicyMetadata policy = new BackupPolicyMetadata();
        Instant now = Instant.now();
        policy.setPolicyId("bpol-" + shortId());
        policy.setProjectName(database.getProjectName());
        policy.setDatabaseId(database.getDatabaseId());
        // Database creation already has its own operation. Keeping this null
        // prevents policy reconciliation from overwriting create progress.
        apply(policy, database, desired, null, "create:" + database.getDatabaseId(),
                hash(desired.repository(), String.valueOf(desired.autoBackupEnabled()),
                        String.valueOf(desired.retentionDays()), String.valueOf(desired.cronExpression()),
                        desired.timezone(), desired.retentionPolicy().name(),
                        String.valueOf(desired.pitrEnabled())), now);
        return policy;
    }

    public BackupPolicyResponse response(BackupPolicyMetadata policy) {
        String message = policy.getFailureMessage();
        if (message == null || message.isBlank()) {
            message = switch (policy.getPolicyStatus()) {
                case PENDING -> "KubeBlocks backup policy is being reconciled.";
                case ACTIVE -> "KubeBlocks backup policy and schedule are available.";
                case DISABLED -> "Automatic backup is disabled.";
                case FAILED -> "Backup policy reconciliation failed.";
            };
        }
        return new BackupPolicyResponse(policy.getPolicyId(), policy.getProjectName(), policy.getDatabaseId(),
                policy.getEngine(), policy.getBackupRepositoryName(), policy.isAutoBackupEnabled(),
                policy.getRetentionDays(), policy.getCronExpression(), policy.getTimezone(),
                policy.getRetentionPolicy(), policy.isPitrEnabled(), policy.getDefaultBackupMethod(),
                policy.getPolicyStatus(), message, policy.getUpdatedAt(), policy.getContinuousBackupMethod(),
                policy.getPitrStatus() == null ? PitrStatus.DISABLED : policy.getPitrStatus(),
                policy.getRecoverableFrom(), policy.getRecoverableUntil(), policy.getPitrMessage(),
                policy.getPitrObservedAt());
    }

    private void apply(BackupPolicyMetadata policy, DatabaseMetadata database,
                       BackupConfigurationNormalizer.NormalizedBackupConfiguration desired,
                       String operationId, String idempotencyKey, String requestHash, Instant now) {
        BackupEngineStrategy strategy = strategies.require(database.getEngine());
        policy.setEngine(database.getEngine());
        policy.setBackupRepositoryName(desired.repository());
        policy.setDefaultBackupMethod(strategy.manualFullMethod());
        policy.setContinuousBackupMethod(desired.pitrEnabled() ? strategy.continuousMethod() : null);
        policy.setSchedulingEnabled(desired.autoBackupEnabled());
        policy.setAutoBackupEnabled(desired.autoBackupEnabled());
        policy.setDefaultRetentionPeriod(normalizer.duration(desired.retentionDays()));
        policy.setRetentionDays(desired.retentionDays());
        policy.setRetentionPolicy(desired.retentionPolicy());
        policy.setCronExpression(desired.cronExpression());
        policy.setTimezone(desired.timezone());
        policy.setPitrEnabled(desired.pitrEnabled());
        policy.setPitrStatus(desired.pitrEnabled() ? PitrStatus.PENDING : PitrStatus.DISABLED);
        policy.setPitrMessage(desired.pitrEnabled()
                ? "Waiting for a completed base backup and continuous log coverage."
                : "Point-in-time recovery is disabled.");
        policy.setRecoverableFrom(null);
        policy.setRecoverableUntil(null);
        policy.setPitrObservedAt(now);
        policy.setConfigurationApplied(false);
        policy.setPolicyStatus(BackupPolicyStatus.PENDING);
        policy.setObservedStatus("PENDING");
        policy.setPolicyUpdateOperationId(operationId);
        policy.setIdempotencyKey(idempotencyKey);
        policy.setRequestHash(requestHash);
        policy.setFailureCode(null);
        policy.setFailureMessage(null);
        policy.setUpdatedAt(now);
    }

    private void validatePitrSupport(DatabaseMetadata database, BackupEngineStrategy strategy,
                                     BackupConfigurationNormalizer.NormalizedBackupConfiguration desired) {
        if (!desired.pitrEnabled()) return;
        if (!strategy.supportsPitrTopology(database.getMode())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PITR_NOT_SUPPORTED", false,
                    "Point-in-time recovery is not supported for this database topology.");
        }
        kubeBlocksClient.validatePitrTemplate(database.getEngine(), strategy.manualFullMethod(),
                strategy.continuousMethod());
    }

    private AcceptedOperationResponse accepted(String operationId, String policyId) {
        OperationStatus status = operationId == null ? OperationStatus.PENDING : operationRepository.findById(operationId)
                .map(OperationMetadata::getStatus).orElse(OperationStatus.PENDING);
        return new AcceptedOperationResponse(operationId, policyId, status,
                "/api/v1/operations/" + operationId, 5);
    }

    private boolean operationStillActive(String operationId) {
        return operationRepository.findById(operationId)
                .map(operation -> operation.getStatus() == OperationStatus.PENDING
                        || operation.getStatus() == OperationStatus.RUNNING)
                .orElse(false);
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
}
