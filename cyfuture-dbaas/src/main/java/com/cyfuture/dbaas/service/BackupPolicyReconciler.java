package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.entity.BackupPolicyMetadata;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.model.BackupPolicyStatus;
import com.cyfuture.dbaas.model.OperationStatus;
import com.cyfuture.dbaas.model.ProvisioningStage;
import com.cyfuture.dbaas.repository.BackupPolicyMetadataRepository;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import com.cyfuture.dbaas.repository.OperationMetadataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/** Observes live KubeBlocks backup settings and imports scheduled history. */
@Service
@RequiredArgsConstructor
@Slf4j
public class BackupPolicyReconciler {
    private final BackupPolicyMetadataRepository policyRepository;
    private final DatabaseMetadataRepository databaseRepository;
    private final OperationMetadataRepository operationRepository;
    private final KubeBlocksClient kubeBlocksClient;
    private final BackupPolicySubmissionService submissionService;
    private final ScheduledBackupDiscoveryService discoveryService;
    private final PitrRecoveryService pitrRecoveryService;
    private final BackupEngineStrategies strategies;
    private final BackupConfigurationNormalizer normalizer;

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() { reconcile(); }

    @Scheduled(fixedDelayString = "${dbaas.backup.reconcile-ms:5000}")
    public void reconcile() {
        for (BackupPolicyMetadata policy : policyRepository.findByPolicyStatusInOrderByUpdatedAtAsc(
                List.of(BackupPolicyStatus.PENDING, BackupPolicyStatus.ACTIVE, BackupPolicyStatus.DISABLED))) {
            try {
                refresh(policy);
            } catch (Exception exception) {
                if (!BackupRestoreSafety.retryable(exception)) {
                    fail(policy, BackupRestoreSafety.failureCode(exception, "BACKUP_SETTINGS_RECONCILE_FAILED"),
                            BackupRestoreSafety.safeMessage(exception, "Backup settings reconciliation failed."));
                }
                log.debug("Backup settings reconciliation for {} will retry", policy.getDatabaseId());
            }
        }
    }

    /** Read-through reconciliation used by GET settings, backup history, and PITR. */
    public void refresh(BackupPolicyMetadata policy) {
        DatabaseMetadata source = databaseRepository.findByDatabaseIdAndProjectName(
                policy.getDatabaseId(), policy.getProjectName()).orElse(null);
        if (source == null) {
            fail(policy, "BACKUP_SOURCE_METADATA_MISSING", "Backup source metadata is unavailable.");
            return;
        }
        // A create request persists its desired backup settings before the
        // Cluster and its controller-owned BackupSchedule exist. Submit the
        // settings asynchronously, then wait for that write to succeed before
        // treating the generated schedule as active. This prevents a merely
        // available (but still disabled) BackupSchedule from being reported as
        // an enabled scheduled backup.
        if (!policy.isConfigurationApplied()) {
            submissionService.submit(policy.getPolicyId());
            return;
        }
        BackupEngineStrategy strategy = strategies.require(source.getEngine());
        KubeBlocksClient.BackupPolicyInfo observed = kubeBlocksClient.resolveReadyBackupPolicy(
                source.getNamespaceName(), source.getDatabaseId(), source.getEngine(), strategy.manualFullMethod(),
                policy.isPitrEnabled() ? strategy.continuousMethod() : null, normalizer.repositoryName());
        if (!"AVAILABLE".equalsIgnoreCase(observed.observedStatus())) {
            pending(policy, observed.policyName(), null, "Waiting for backup settings to become available.");
            pitrRecoveryService.refresh(policy);
            return;
        }
        if (!policy.isAutoBackupEnabled()) {
            activate(policy, observed.policyName(), null, BackupPolicyStatus.DISABLED,
                    "Scheduled backups are disabled.");
            pitrRecoveryService.refresh(policy);
            return;
        }
        KubeBlocksClient.BackupScheduleInfo schedule = kubeBlocksClient.observeGeneratedBackupSchedule(
                source.getNamespaceName(), source.getDatabaseId(), observed.policyName());
        if (!schedule.exists() || !schedule.available()) {
            pending(policy, observed.policyName(), schedule.scheduleName(),
                    "Waiting for scheduled backups to become available.");
            pitrRecoveryService.refresh(policy);
            return;
        }
        activate(policy, observed.policyName(), schedule.scheduleName(), BackupPolicyStatus.ACTIVE,
                "Scheduled backups are available.");
        discoveryService.discover(policy);
        pitrRecoveryService.refresh(policy);
    }

    private void pending(BackupPolicyMetadata policy, String policyName, String scheduleName, String message) {
        policy.setPolicyStatus(BackupPolicyStatus.PENDING);
        policy.setKubernetesPolicyName(policyName);
        policy.setKubernetesScheduleName(scheduleName);
        policy.setLastObservedAt(Instant.now());
        policy.setUpdatedAt(Instant.now());
        policyRepository.save(policy);
        updateOperation(policy, OperationStatus.RUNNING, 60, message, false);
    }

    private void activate(BackupPolicyMetadata policy, String policyName, String scheduleName,
                          BackupPolicyStatus status, String message) {
        policy.setPolicyStatus(status);
        policy.setKubernetesPolicyName(policyName);
        policy.setKubernetesScheduleName(scheduleName);
        policy.setFailureCode(null);
        policy.setFailureMessage(null);
        policy.setLastObservedAt(Instant.now());
        policy.setUpdatedAt(Instant.now());
        policyRepository.save(policy);
        updateOperation(policy, OperationStatus.SUCCEEDED, 100, message, true);
    }

    private void fail(BackupPolicyMetadata policy, String code, String message) {
        policy.setPolicyStatus(BackupPolicyStatus.FAILED);
        policy.setFailureCode(code);
        policy.setFailureMessage(message);
        policy.setUpdatedAt(Instant.now());
        policyRepository.save(policy);
        updateOperation(policy, OperationStatus.FAILED, 100, "Backup settings update failed.", true);
    }

    private void updateOperation(BackupPolicyMetadata policy, OperationStatus status,
                                 int progress, String message, boolean terminal) {
        if (policy.getPolicyUpdateOperationId() == null) return;
        operationRepository.findById(policy.getPolicyUpdateOperationId()).ifPresent(operation -> {
            operation.setStatus(status);
            operation.setProvisioningStage(terminal && status == OperationStatus.SUCCEEDED
                    ? ProvisioningStage.READY : terminal ? ProvisioningStage.FAILED
                    : ProvisioningStage.WAITING_FOR_REPLICAS);
            operation.setProgress(progress);
            operation.setMessage(message);
            if (operation.getStartedAt() == null) operation.setStartedAt(Instant.now());
            if (terminal) operation.setCompletedAt(Instant.now());
            operationRepository.save(operation);
        });
    }
}
