package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.dto.BackupSettingsRequest;
import com.cyfuture.dbaas.dto.BackupSettingsResponse;
import com.cyfuture.dbaas.entity.BackupPolicyMetadata;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.entity.OperationMetadata;
import com.cyfuture.dbaas.exception.ApiException;
import com.cyfuture.dbaas.model.BackupPolicyStatus;
import com.cyfuture.dbaas.model.OperationStatus;
import com.cyfuture.dbaas.model.OperationType;
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

/** Applies product-level backup settings; KubeBlocks child resources remain private. */
@Service
@RequiredArgsConstructor
public class BackupPolicyService {
    private final BackupPolicyMetadataRepository policyRepository;
    private final DatabaseMetadataRepository databaseRepository;
    private final OperationMetadataRepository operationRepository;
    private final ProjectService projectService;
    private final BackupConfigurationNormalizer normalizer;
    private final BackupEngineStrategies strategies;
    private final com.cyfuture.dbaas.client.KubeBlocksClient kubeBlocksClient;
    private final BackupPolicySubmissionService submissionService;
    private final BackupPolicyReconciler policyReconciler;
    private final PitrRecoveryService pitrRecoveryService;

    @Transactional
    public BackupSettingsResponse update(String project, String databaseId, BackupSettingsRequest request) {
        projectService.requireActiveProject(project);
        DatabaseMetadata database = databaseRepository.findByDatabaseIdAndProjectNameForUpdate(databaseId, project)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "DATABASE_NOT_FOUND", false,
                        "Database was not found in this project."));
        if (database.getStatus() == com.cyfuture.dbaas.model.DatabaseStatus.DELETING
                || database.getStatus() == com.cyfuture.dbaas.model.DatabaseStatus.DELETED) {
            throw new ApiException(HttpStatus.CONFLICT, "DATABASE_DELETION_IN_PROGRESS", false,
                    "Backup settings cannot be changed while the database is being deleted.");
        }
        BackupEngineStrategy strategy = strategies.require(database.getEngine());
        if (!strategy.supportsTopology(database.getMode())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "BACKUP_TOPOLOGY_UNSUPPORTED", false,
                    "The installed backup template does not support this database topology.");
        }
        BackupConfigurationNormalizer.NormalizedBackupConfiguration desired = normalizer.normalize(request);
        kubeBlocksClient.validateReadyBackupRepository(normalizer.repositoryName());
        validatePitrSupport(database, strategy, desired);
        String requestHash = hash(String.valueOf(desired.autoBackupEnabled()),
                String.valueOf(desired.retentionDays()), String.valueOf(desired.cronExpression()),
                desired.timezone(), String.valueOf(desired.pitrEnabled()));

        BackupPolicyMetadata policy = policyRepository.findByProjectNameAndDatabaseId(project, databaseId)
                .orElse(null);
        if (policy != null && policy.getPolicyUpdateOperationId() != null
                && operationStillActive(policy.getPolicyUpdateOperationId())) {
            throw new ApiException(HttpStatus.CONFLICT, "BACKUP_SETTINGS_UPDATE_IN_PROGRESS", true,
                    "Backup settings are still being applied.");
        }

        Instant now = Instant.now();
        if (policy == null) {
            policy = new BackupPolicyMetadata();
            policy.setPolicyId("bks-" + shortId());
            policy.setProjectName(project);
            policy.setDatabaseId(databaseId);
            policy.setCreatedAt(now);
        }
        String operationId = "op-" + shortId();
        apply(policy, desired, operationId, now);
        policyRepository.save(policy);
        operationRepository.save(OperationMetadata.builder()
                .operationId(operationId)
                .databaseId(databaseId)
                .projectName(project)
                .type(OperationType.BACKUP_POLICY_UPDATE)
                .status(OperationStatus.PENDING)
                .provisioningStage(ProvisioningStage.QUEUED)
                .progress(0)
                .message("Backup settings update queued")
                .requestHash(requestHash)
                .createdAt(now)
                .build());
        String policyId = policy.getPolicyId();
        submitAfterCommit(() -> submissionService.submit(policyId));
        return response(policy);
    }

    public BackupSettingsResponse get(String project, String databaseId) {
        projectService.requireActiveProject(project);
        return response(refresh(project, databaseId));
    }

    /** Used before recovery-window and restore reads to observe KubeBlocks now. */
    public BackupPolicyMetadata refresh(String project, String databaseId) {
        BackupPolicyMetadata policy = policyRepository.findByProjectNameAndDatabaseId(project, databaseId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "BACKUP_SETTINGS_NOT_CONFIGURED", false,
                        "Backup settings have not been configured for this database."));
        policyReconciler.refresh(policy);
        pitrRecoveryService.refresh(policy);
        return policyRepository.findById(policy.getPolicyId()).orElse(policy);
    }

    /** Returns the normalized form stored with a newly created database. */
    public BackupSettingsRequest normalizeForCreation(BackupSettingsRequest request) {
        BackupConfigurationNormalizer.NormalizedBackupConfiguration desired = normalizer.normalize(request);
        return new BackupSettingsRequest(desired.autoBackupEnabled(), desired.retentionDays(),
                desired.cronExpression(), desired.timezone(), desired.pitrEnabled());
    }

    /** Builds pending settings to save atomically with database creation metadata. */
    public BackupPolicyMetadata initialPolicy(DatabaseMetadata database,
                                              BackupSettingsRequest request,
                                              String databaseOperationId) {
        BackupConfigurationNormalizer.NormalizedBackupConfiguration desired = normalizer.normalize(request);
        kubeBlocksClient.validateReadyBackupRepository(normalizer.repositoryName());
        BackupEngineStrategy strategy = strategies.require(database.getEngine());
        if (!strategy.supportsTopology(database.getMode())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "BACKUP_TOPOLOGY_UNSUPPORTED", false,
                    "The installed backup template does not support this database topology.");
        }
        validatePitrSupport(database, strategy, desired);
        BackupPolicyMetadata policy = new BackupPolicyMetadata();
        Instant now = Instant.now();
        policy.setPolicyId("bks-" + shortId());
        policy.setProjectName(database.getProjectName());
        policy.setDatabaseId(database.getDatabaseId());
        policy.setCreatedAt(now);
        // Database creation owns the visible operation. Settings reconcile independently afterwards.
        apply(policy, desired, null, now);
        return policy;
    }

    public BackupSettingsResponse response(BackupPolicyMetadata policy) {
        return new BackupSettingsResponse(policy.getDatabaseId(), policy.isAutoBackupEnabled(),
                policy.getRetentionDays(), policy.getCronExpression(), policy.getTimezone(), policy.isPitrEnabled(),
                policy.getPolicyStatus().name(), policy.getUpdatedAt(), policy.getFailureCode(),
                policy.getFailureMessage());
    }

    private void apply(BackupPolicyMetadata policy,
                       BackupConfigurationNormalizer.NormalizedBackupConfiguration desired,
                       String operationId, Instant now) {
        policy.setAutoBackupEnabled(desired.autoBackupEnabled());
        policy.setRetentionDays(desired.retentionDays());
        policy.setCronExpression(desired.cronExpression());
        policy.setTimezone(desired.timezone());
        policy.setPitrEnabled(desired.pitrEnabled());
        policy.setConfigurationApplied(false);
        policy.setPolicyStatus(BackupPolicyStatus.PENDING);
        policy.setPolicyUpdateOperationId(operationId);
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

    private boolean operationStillActive(String operationId) {
        return operationRepository.findById(operationId)
                .map(operation -> operation.getStatus() == OperationStatus.PENDING
                        || operation.getStatus() == OperationStatus.RUNNING)
                .orElse(false);
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
