package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.entity.OperationMetadata;
import com.cyfuture.dbaas.model.DatabaseStatus;
import com.cyfuture.dbaas.model.OperationStatus;
import com.cyfuture.dbaas.model.OperationType;
import com.cyfuture.dbaas.model.ProvisioningStage;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import com.cyfuture.dbaas.repository.OperationMetadataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class DatabaseStateReconciler {
    private static final String LOCK_NAME = "cyfuture-dbaas-state-reconciler";
    private static final List<DatabaseStatus> RECONCILED_STATUSES = List.of(
            DatabaseStatus.PROVISIONING,
            DatabaseStatus.RUNNING,
            DatabaseStatus.DEGRADED,
            DatabaseStatus.DELETING,
            DatabaseStatus.MISSING);

    private final DatabaseMetadataRepository databaseRepository;
    private final OperationMetadataRepository operationRepository;
    private final KubeBlocksClient kubeBlocksClient;
    private final SharedGatewayService sharedGatewayService;
    private final CredentialLifecycleService credentialLifecycleService;
    private final DataSource dataSource;

    @Value("${dbaas.state.degraded-grace-ms:30000}")
    private long degradedGraceMs;
    @Value("${dbaas.state.missing-grace-ms:60000}")
    private long missingGraceMs;

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        reconcile();
    }

    @Scheduled(fixedDelayString = "${dbaas.state-reconcile-ms:10000}")
    public void reconcile() {
        try (Connection lockConnection = dataSource.getConnection()) {
            if (!tryLock(lockConnection)) return;
            try {
                for (DatabaseMetadata database : databaseRepository
                        .findByStatusInOrderByCreatedAtAsc(RECONCILED_STATUSES)) {
                    reconcile(database);
                }
            } finally {
                unlock(lockConnection);
            }
        } catch (Exception exception) {
            log.warn("Database state reconciliation could not acquire lock: {}",
                    exception.getMessage());
        }
    }

    void reconcile(DatabaseMetadata database) {
        DirtyFlag.ORIGINAL.set(fingerprint(database));
        try {
            if (database.getStatus() == DatabaseStatus.DELETING) {
                reconcileDeletion(database);
                return;
            }
            observe(database);
        } finally {
            DirtyFlag.ORIGINAL.remove();
            DirtyFlag.CHANGED.remove();
        }
    }

    private void observe(DatabaseMetadata database) {
        try {
            KubeBlocksClient.ClusterObservation observed = kubeBlocksClient.observeCluster(
                    database.getNamespaceName(), database.getDatabaseId());
            if (!observed.exists()) {
                handleMissing(database);
                return;
            }
            syncObserved(database, observed);
            if (observed.healthy()) {
                handleHealthy(database, observed);
            } else {
                handleUnhealthy(database, observed);
            }
            saveIfChanged(database);
        } catch (Exception exception) {
            log.debug("Database state reconciliation for {} will retry: {}",
                    database.getDatabaseId(), exception.getMessage());
        }
    }

    private void handleHealthy(DatabaseMetadata database,
                               KubeBlocksClient.ClusterObservation observed) {
        update(database::setMissingSince, null);
        update(database::setDegradedSince, null);
        if (database.getStatus() == DatabaseStatus.DEGRADED
                || database.getStatus() == DatabaseStatus.MISSING) {
            update(database::setStatus, DatabaseStatus.RUNNING);
            update(database::setProvisioningStage, ProvisioningStage.READY);
            update(database::setProgress, 100);
            update(database::setMessage, "Database recovered and is ready");
        }
    }

    private void handleUnhealthy(DatabaseMetadata database,
                                 KubeBlocksClient.ClusterObservation observed) {
        if (database.getStatus() == DatabaseStatus.PROVISIONING) {
            return;
        }
        if (hasActiveLifecycleOperation(database)) {
            return;
        }
        Instant now = Instant.now();
        if (database.getDegradedSince() == null) {
            update(database::setDegradedSince, now);
            return;
        }
        if (elapsed(database.getDegradedSince(), now, degradedGraceMs)
                && database.getStatus() == DatabaseStatus.RUNNING) {
            update(database::setStatus, DatabaseStatus.DEGRADED);
            update(database::setMessage, "Database health is degraded");
        }
    }

    private void handleMissing(DatabaseMetadata database) {
        Instant now = Instant.now();
        update(database::setObservedReadyReplicas, 0);
        update(database::setObservedServiceReady, false);
        update(database::setLastObservedAt, now);
        if (database.getMissingSince() == null) {
            update(database::setMissingSince, now);
            saveIfChanged(database);
            return;
        }
        if (elapsed(database.getMissingSince(), now, missingGraceMs)
                && database.getStatus() != DatabaseStatus.MISSING) {
            update(database::setStatus, DatabaseStatus.MISSING);
            update(database::setMessage, "Database resource is unavailable");
            try {
                sharedGatewayService.removeRoute(database);
            } catch (Exception exception) {
                log.debug("Public-route removal for missing database {} will retry: {}",
                        database.getDatabaseId(), exception.getMessage());
            }
        }
        saveIfChanged(database);
    }

    private void reconcileDeletion(DatabaseMetadata database) {
        try {
            if (database.getDeleteRequestedAt() == null) {
                update(database::setDeleteRequestedAt, Instant.now());
            }
            sharedGatewayService.removeRoute(database);
            CredentialLifecycleService.CredentialCleanupObservation credentialCleanup =
                    credentialLifecycleService.cleanupDatabaseResources(database);
            kubeBlocksClient.requestDelete(database.getNamespaceName(), database.getDatabaseId());
            KubeBlocksClient.ClusterObservation observed = kubeBlocksClient.observeCluster(
                    database.getNamespaceName(), database.getDatabaseId());
            if (!observed.exists()) {
                if (!credentialCleanup.complete()) {
                    update(database::setMessage,
                            "Database Cluster is absent; waiting for credential helper cleanup: "
                                    + credentialCleanup.message());
                    finishDeleteOperation(database, OperationStatus.RUNNING, credentialCleanup.message());
                    saveIfChanged(database);
                    return;
                }
                sharedGatewayService.releasePort(database);
                update(database::setStatus, DatabaseStatus.DELETED);
                update(database::setDeletedAt, Instant.now());
                update(database::setMessage, "Database Cluster and credential helper resources are absent; metadata is preserved");
                finishDeleteOperation(database, OperationStatus.SUCCEEDED,
                        "Deletion confirmed by Kubernetes");
            } else {
                syncObserved(database, observed);
                update(database::setMessage, "KubeBlocks deletion is running");
                finishDeleteOperation(database, OperationStatus.RUNNING,
                        "KubeBlocks deletion is running");
            }
            saveIfChanged(database);
        } catch (Exception exception) {
            update(database::setMessage, "Database deletion is waiting for synchronization retry");
            saveIfChanged(database);
            log.debug("Deletion reconciliation for {} will retry: {}",
                    database.getDatabaseId(), exception.getMessage());
        }
    }

    private void syncObserved(DatabaseMetadata database,
                              KubeBlocksClient.ClusterObservation observed) {
        update(database::setExpectedReplicas, observed.expectedReplicas());
        update(database::setObservedReadyReplicas, observed.readyReplicas());
        update(database::setObservedServiceReady, observed.serviceReady());
        update(database::setLastObservedAt, Instant.now());
    }

    private boolean hasActiveLifecycleOperation(DatabaseMetadata database) {
        return operationRepository.findByDatabaseIdAndProjectNameAndStatusIn(
                        database.getDatabaseId(),
                        database.getProjectName(),
                        List.of(OperationStatus.PENDING, OperationStatus.RUNNING))
                .stream()
                .anyMatch(operation -> operation.getType() != OperationType.CREATE
                        && operation.getType() != OperationType.DELETE);
    }

    private void finishDeleteOperation(DatabaseMetadata database,
                                       OperationStatus status,
                                       String message) {
        operationRepository.findByDatabaseIdAndProjectNameAndStatusIn(
                        database.getDatabaseId(), database.getProjectName(),
                        List.of(OperationStatus.PENDING, OperationStatus.RUNNING))
                .stream()
                .filter(operation -> operation.getType() == OperationType.DELETE)
                .findFirst()
                .ifPresent(operation -> updateDeleteOperation(operation, status, message));
    }

    private void updateDeleteOperation(OperationMetadata operation,
                                       OperationStatus status,
                                       String message) {
        operation.setStatus(status);
        operation.setProvisioningStage(status == OperationStatus.SUCCEEDED
                ? ProvisioningStage.READY : ProvisioningStage.WAITING_FOR_REPLICAS);
        operation.setProgress(status == OperationStatus.SUCCEEDED ? 100 : 50);
        operation.setMessage(message);
        if (operation.getStartedAt() == null) operation.setStartedAt(Instant.now());
        if (status == OperationStatus.SUCCEEDED) operation.setCompletedAt(Instant.now());
        operationRepository.save(operation);
    }

    private boolean elapsed(Instant start, Instant now, long millis) {
        return !Duration.between(start, now).minusMillis(millis).isNegative();
    }

    private boolean tryLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT GET_LOCK(?, 0)")) {
            statement.setString(1, LOCK_NAME);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getInt(1) == 1;
            }
        }
    }

    private void unlock(Connection connection) {
        try {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT RELEASE_LOCK(?)")) {
                statement.setString(1, LOCK_NAME);
                statement.executeQuery();
            }
        } catch (Exception exception) {
            log.debug("Database state reconciliation advisory unlock failed: {}",
                    exception.getMessage());
        }
    }

    private void saveIfChanged(DatabaseMetadata database) {
        if (!Boolean.TRUE.equals(DirtyFlag.CHANGED.get())) return;
        if (Objects.equals(DirtyFlag.ORIGINAL.get(), fingerprint(database))) return;
        database.setUpdatedAt(Instant.now());
        databaseRepository.save(database);
        DirtyFlag.CHANGED.set(false);
    }

    private <T> void update(java.util.function.Consumer<T> setter, T value) {
        setter.accept(value);
        DirtyFlag.CHANGED.set(true);
    }

    private String fingerprint(DatabaseMetadata database) {
        return String.join("|",
                String.valueOf(database.getDesiredState()),
                String.valueOf(database.getStatus()),
                String.valueOf(database.getExpectedReplicas()),
                String.valueOf(database.getObservedReadyReplicas()),
                String.valueOf(database.isObservedServiceReady()),
                String.valueOf(database.getLastObservedAt()),
                String.valueOf(database.getMissingSince()),
                String.valueOf(database.getDegradedSince()),
                String.valueOf(database.getDeleteRequestedAt()),
                String.valueOf(database.getDeletedAt()),
                String.valueOf(database.getMessage()),
                String.valueOf(database.getPublicPort()));
    }

    private static final class DirtyFlag {
        private static final ThreadLocal<String> ORIGINAL = new ThreadLocal<>();
        private static final ThreadLocal<Boolean> CHANGED = ThreadLocal.withInitial(() -> false);
    }
}
