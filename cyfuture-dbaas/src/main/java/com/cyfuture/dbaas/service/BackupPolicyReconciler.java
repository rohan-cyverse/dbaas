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
import java.util.Objects;

/** Restart-safe observer for Cluster.spec.backup and KubeBlocks-generated policy/schedule children. */
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

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        reconcile();
    }

    @Scheduled(fixedDelayString = "${dbaas.backup.reconcile-ms:5000}")
    public void reconcile() {
        for (BackupPolicyMetadata policy : policyRepository.findByPolicyStatusInOrderByUpdatedAtAsc(
                List.of(BackupPolicyStatus.PENDING, BackupPolicyStatus.ACTIVE, BackupPolicyStatus.DISABLED))) {
            try {
                reconcile(policy);
            } catch (Exception exception) {
                if (!BackupRestoreSafety.retryable(exception)) {
                    fail(policy, BackupRestoreSafety.failureCode(exception, "BACKUP_POLICY_RECONCILE_FAILED"),
                            BackupRestoreSafety.safeMessage(exception, "Backup policy reconciliation failed."));
                }
                log.debug("Backup policy reconciliation for {} will retry: {}",
                        policy.getPolicyId(), BackupRestoreSafety.safeMessage(exception,
                                "Backup policy reconciliation will retry."));
            }
        }
    }

    void reconcile(BackupPolicyMetadata policy) {
        DatabaseMetadata source = databaseRepository.findByDatabaseIdAndProjectName(
                policy.getDatabaseId(), policy.getProjectName()).orElse(null);
        if (source == null) {
            fail(policy, "BACKUP_SOURCE_METADATA_MISSING", "Backup source metadata is unavailable.");
            return;
        }
        if (policy.getPolicyStatus() == BackupPolicyStatus.PENDING
                || policy.getKubernetesPolicyName() == null) {
            submissionService.submit(policy.getPolicyId());
        }
        KubeBlocksClient.BackupPolicyInfo observed = kubeBlocksClient.resolveReadyBackupPolicy(
                source.getNamespaceName(), source.getDatabaseId(), source.getEngine(),
                policy.getDefaultBackupMethod(), policy.isPitrEnabled()
                        ? policy.getContinuousBackupMethod() : null,
                policy.getBackupRepositoryName());
        if (!"AVAILABLE".equalsIgnoreCase(observed.observedStatus())) {
            pending(policy, observed, null, "Waiting for generated KubeBlocks BackupPolicy");
            pitrRecoveryService.refresh(policy);
            return;
        }
        if (policy.isAutoBackupEnabled()) {
            KubeBlocksClient.BackupScheduleInfo schedule = kubeBlocksClient.observeGeneratedBackupSchedule(
                    source.getNamespaceName(), source.getDatabaseId(), observed.policyName());
            if (!schedule.exists() || !schedule.available()) {
                pending(policy, observed, schedule, "Waiting for generated KubeBlocks BackupSchedule");
                return;
            }
            activate(policy, observed, schedule, BackupPolicyStatus.ACTIVE,
                    "Backup policy and schedule are available.");
            discoveryService.discover(policy);
            pitrRecoveryService.refresh(policy);
            return;
        }
        // A disabled schedule has no generated BackupSchedule to wait for, but
        // the generated BackupPolicy must still be available before it is stable.
        activate(policy, observed, null, BackupPolicyStatus.DISABLED,
                "Automatic backup is disabled.");
        pitrRecoveryService.refresh(policy);
    }

    private void pending(BackupPolicyMetadata policy, KubeBlocksClient.BackupPolicyInfo observed,
                         KubeBlocksClient.BackupScheduleInfo schedule, String message) {
        boolean changed = policy.getPolicyStatus() != BackupPolicyStatus.PENDING
                || !Objects.equals(policy.getKubernetesPolicyName(), observed.policyName())
                || !Objects.equals(policy.getObservedStatus(), observed.observedStatus())
                || (schedule != null && !Objects.equals(policy.getKubernetesScheduleName(), schedule.scheduleName()));
        if (changed) {
            policy.setPolicyStatus(BackupPolicyStatus.PENDING);
            policy.setKubernetesPolicyName(observed.policyName());
            policy.setKubernetesScheduleName(schedule == null ? null : schedule.scheduleName());
            policy.setObservedStatus(schedule == null ? observed.observedStatus() : schedule.observedStatus());
            policy.setLastObservedAt(Instant.now());
            policy.setUpdatedAt(Instant.now());
            policyRepository.save(policy);
        }
        updateOperation(policy, OperationStatus.RUNNING, 60, message, false);
    }

    private void activate(BackupPolicyMetadata policy, KubeBlocksClient.BackupPolicyInfo observed,
                          KubeBlocksClient.BackupScheduleInfo schedule, BackupPolicyStatus status,
                          String message) {
        boolean changed = policy.getPolicyStatus() != status
                || !Objects.equals(policy.getKubernetesPolicyName(), observed.policyName())
                || !Objects.equals(policy.getObservedStatus(), observed.observedStatus())
                || !Objects.equals(policy.getKubernetesScheduleName(),
                        schedule == null ? null : schedule.scheduleName())
                || policy.getFailureCode() != null || policy.getFailureMessage() != null;
        if (changed) {
            policy.setPolicyStatus(status);
            policy.setKubernetesPolicyName(observed.policyName());
            policy.setKubernetesScheduleName(schedule == null ? null : schedule.scheduleName());
            policy.setObservedStatus(observed.observedStatus());
            if (observed.continuousMethod() != null && !observed.continuousMethod().isBlank()) {
                policy.setContinuousBackupMethod(observed.continuousMethod());
            }
            policy.setFailureCode(null);
            policy.setFailureMessage(null);
            policy.setLastObservedAt(Instant.now());
            policy.setUpdatedAt(Instant.now());
            policyRepository.save(policy);
        }
        updateOperation(policy, OperationStatus.SUCCEEDED, 100, message, true);
    }

    private void fail(BackupPolicyMetadata policy, String code, String message) {
        if (policy.getPolicyStatus() == BackupPolicyStatus.FAILED
                && Objects.equals(policy.getFailureCode(), code)
                && Objects.equals(policy.getFailureMessage(), message)) return;
        policy.setPolicyStatus(BackupPolicyStatus.FAILED);
        policy.setFailureCode(code);
        policy.setFailureMessage(message);
        policy.setUpdatedAt(Instant.now());
        policyRepository.save(policy);
        updateOperation(policy, OperationStatus.FAILED, 100, "Backup policy update failed.", true);
    }

    private void updateOperation(BackupPolicyMetadata policy, OperationStatus status,
                                 int progress, String message, boolean terminal) {
        if (policy.getPolicyUpdateOperationId() == null) return;
        operationRepository.findById(policy.getPolicyUpdateOperationId()).ifPresent(operation -> {
            if (operation.getStatus() == status && operation.getProgress() == progress
                    && Objects.equals(operation.getMessage(), message)) return;
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
