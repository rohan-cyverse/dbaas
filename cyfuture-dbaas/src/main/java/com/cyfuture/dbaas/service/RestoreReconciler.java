package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.dto.PublicEndpointResponse;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.entity.RestoreRequestMetadata;
import com.cyfuture.dbaas.model.DatabaseStatus;
import com.cyfuture.dbaas.model.OperationStatus;
import com.cyfuture.dbaas.model.ProvisioningStage;
import com.cyfuture.dbaas.model.RestoreStatus;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import com.cyfuture.dbaas.repository.OperationMetadataRepository;
import com.cyfuture.dbaas.repository.RestoreRequestMetadataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Completes a restore only after KubeBlocks, credentials, and the public route are all ready. */
@Service
@RequiredArgsConstructor
@Slf4j
public class RestoreReconciler {
    private final RestoreRequestMetadataRepository restoreRepository;
    private final DatabaseMetadataRepository databaseRepository;
    private final OperationMetadataRepository operationRepository;
    private final KubeBlocksClient kubeBlocksClient;
    private final CredentialLifecycleService credentialLifecycleService;
    private final SharedGatewayService sharedGatewayService;
    private final ProvisioningProgressService progressService;
    private final RestoreSubmissionService submissionService;

    @Value("${dbaas.restore-timeout-ms:3600000}")
    private long restoreTimeoutMs = 3_600_000L;

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() { reconcile(); }

    @Scheduled(fixedDelayString = "${dbaas.backup.reconcile-ms:5000}")
    public void reconcile() {
        for (RestoreRequestMetadata restore : restoreRepository.findByStatusInOrderByCreatedAtAsc(
                List.of(RestoreStatus.PENDING, RestoreStatus.RUNNING))) {
            try {
                if (restore.getStatus() == RestoreStatus.PENDING) {
                    submissionService.submit(restore.getRestoreId());
                } else {
                    reconcile(restore);
                }
            } catch (Exception exception) {
                if (timedOut(restore)) {
                    fail(restore, "RESTORE_TIMEOUT", "Restore timed out before readiness was verified.");
                }
                log.debug("Restore reconciliation for {} will retry: {}", restore.getRestoreId(), exception.getMessage());
            }
        }
    }

    void reconcile(RestoreRequestMetadata restore) {
        DatabaseMetadata target = databaseRepository.findByDatabaseIdAndProjectName(
                restore.getRestoredDatabaseId(), restore.getProjectName()).orElse(null);
        if (target == null) {
            fail(restore, "RESTORE_TARGET_METADATA_MISSING", "Restore target metadata is unavailable.");
            return;
        }
        KubeBlocksClient.OpsRequestInfo observed = kubeBlocksClient.getOpsRequest(
                target.getNamespaceName(), restore.getKubernetesOpsRequestName());
        if (failedPhase(observed.phase())) {
            fail(restore, "KUBERNETES_RESTORE_FAILED", observed.message());
            return;
        }
        if (!"Succeed".equalsIgnoreCase(observed.phase())) {
            updateRunning(restore, observed, 45, "KubeBlocks is restoring database data");
            return;
        }

        KubeBlocksClient.ClusterObservation cluster = kubeBlocksClient.observeCluster(
                target.getNamespaceName(), target.getDatabaseId());
        if (!cluster.exists()) {
            updateRunning(restore, observed, 60, "Waiting for the restored database Cluster");
            return;
        }
        if ("Failed".equalsIgnoreCase(cluster.phase())) {
            fail(restore, "KUBERNETES_RESTORED_CLUSTER_FAILED", cluster.message());
            return;
        }
        if (!cluster.healthy()) {
            updateRunning(restore, observed, 70, "Waiting for restored database replicas");
            return;
        }
        if (!credentialLifecycleService.ready(target)) {
            updateRunning(restore, observed, 82, "Creating restored database credentials");
            return;
        }
        PublicEndpointResponse endpoint = sharedGatewayService.configure(target);
        if (!endpoint.ready()) {
            updateRunning(restore, observed, 92, "Waiting for the restored public connection route");
            return;
        }
        progressService.ready(target);
        restore.setStatus(RestoreStatus.COMPLETED);
        restore.setCompletedAt(Instant.now());
        restore.setLastObservedAt(Instant.now());
        restore.setFailureCode(null);
        restore.setFailureMessage(null);
        restoreRepository.save(restore);
    }

    private void updateRunning(RestoreRequestMetadata restore, KubeBlocksClient.OpsRequestInfo observed,
                               int fallbackProgress, String operationMessage) {
        int progress = progress(observed.progress(), fallbackProgress);
        boolean changed = restore.getStatus() != RestoreStatus.RUNNING
                || !Objects.equals(restore.getStartedAt(), observed.startedAt());
        if (changed) {
            restore.setStatus(RestoreStatus.RUNNING);
            if (observed.startedAt() != null) restore.setStartedAt(observed.startedAt());
            restore.setLastObservedAt(Instant.now());
            restoreRepository.save(restore);
        }
        operationRepository.findById(restore.getOperationId()).ifPresent(operation -> {
            if (operation.getStatus() == OperationStatus.RUNNING && operation.getProgress() == progress
                    && Objects.equals(operation.getMessage(), operationMessage)) return;
            operation.setStatus(OperationStatus.RUNNING);
            operation.setProvisioningStage(ProvisioningStage.RESTORING_DATA);
            operation.setProgress(progress);
            operation.setMessage(operationMessage);
            if (operation.getStartedAt() == null) operation.setStartedAt(Instant.now());
            operationRepository.save(operation);
        });
    }

    private void fail(RestoreRequestMetadata restore, String code, String message) {
        restore.setStatus(RestoreStatus.FAILED);
        restore.setFailureCode(code);
        restore.setFailureMessage(BackupRestoreSafety.safeMessage(null, message));
        restore.setCompletedAt(Instant.now());
        restore.setLastObservedAt(Instant.now());
        restoreRepository.save(restore);
        databaseRepository.findByDatabaseIdAndProjectName(restore.getRestoredDatabaseId(), restore.getProjectName())
                .ifPresent(target -> {
                    target.setStatus(DatabaseStatus.FAILED);
                    target.setProvisioningStage(ProvisioningStage.FAILED);
                    target.setProgress(100);
                    target.setMessage("Restore failed.");
                    target.setUpdatedAt(Instant.now());
                    databaseRepository.save(target);
                });
        operationRepository.findById(restore.getOperationId()).ifPresent(operation -> {
            operation.setStatus(OperationStatus.FAILED);
            operation.setProvisioningStage(ProvisioningStage.FAILED);
            operation.setProgress(100);
            operation.setMessage("Restore failed.");
            operation.setCompletedAt(Instant.now());
            operationRepository.save(operation);
        });
    }

    private boolean failedPhase(String phase) {
        return "Failed".equalsIgnoreCase(phase) || "Cancelled".equalsIgnoreCase(phase)
                || "Aborted".equalsIgnoreCase(phase);
    }

    private int progress(String value, int fallback) {
        if (value == null || !value.contains("/")) return fallback;
        String[] values = value.split("/", 2);
        try {
            int done = Integer.parseInt(values[0]);
            int total = Integer.parseInt(values[1]);
            return total <= 0 ? fallback : Math.max(20, Math.min(95, done * 100 / total));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private boolean timedOut(RestoreRequestMetadata restore) {
        Instant started = restore.getStartedAt() == null ? restore.getCreatedAt() : restore.getStartedAt();
        return started != null && !started.plusMillis(restoreTimeoutMs).isAfter(Instant.now());
    }
}
