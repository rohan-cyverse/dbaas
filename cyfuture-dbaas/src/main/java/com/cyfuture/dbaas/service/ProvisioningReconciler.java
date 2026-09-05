package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.client.DatabaseObservation;
import com.cyfuture.dbaas.dto.PublicEndpointResponse;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.exception.ApiException;
import com.cyfuture.dbaas.model.DatabaseStatus;
import com.cyfuture.dbaas.model.ProvisioningStage;
import com.cyfuture.dbaas.model.DesiredState;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Advances create operations until the database is actually connectable. */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProvisioningReconciler {
    private final DatabaseMetadataRepository databaseRepository;
    private final KubeBlocksClient kubeBlocksClient;
    private final CredentialLifecycleService credentialLifecycleService;
    private final ProvisioningProgressService progressService;
    private final SharedGatewayService sharedGatewayService;

    @Scheduled(fixedDelayString = "${dbaas.provisioning-reconcile-ms:5000}")
    public void reconcile() {
        for (DatabaseMetadata database : databaseRepository
                .findByStatusOrderByCreatedAtAsc(DatabaseStatus.PROVISIONING)) {
            reconcile(database);
        }
    }

    void reconcile(DatabaseMetadata database) {
        if (database.getDesiredState() == DesiredState.DELETED
                || database.getStatus() == DatabaseStatus.DELETING
                || database.getStatus() == DatabaseStatus.DELETED) return;
        try {
            DatabaseObservation live = kubeBlocksClient.get(
                    database.getNamespaceName(), database.getDatabaseId());
            if (live.status() == DatabaseStatus.FAILED) {
                progressService.failed(database, live.message());
                return;
            }
            if (live.status() != DatabaseStatus.RUNNING
                    || !live.serviceReady()) {
                progressService.update(database, ProvisioningStage.WAITING_FOR_REPLICAS, 45,
                        live.message());
                return;
            }

            progressService.update(database, ProvisioningStage.CREATING_CREDENTIALS, 65,
                    "Creating a dedicated least-privilege database user");
            if (!credentialLifecycleService.ready(database)) return;

            progressService.update(database, ProvisioningStage.CONFIGURING_NETWORK, 80,
                    "Activating a route on the shared public gateway");
            PublicEndpointResponse endpoint = sharedGatewayService.configure(database);
            if (!endpoint.ready()) return;

            progressService.update(database, ProvisioningStage.VERIFYING_CONNECTION, 95,
                    "Verifying credentials and database service endpoints");
            if (!credentialLifecycleService.ready(database)) return;
            progressService.ready(database);
        } catch (ApiException exception) {
            if (exception.getMessage() != null
                    && exception.getMessage().startsWith(
                    "Managed credential provisioning failed")) {
                progressService.failed(database, exception.getMessage());
                return;
            }
            if (exception.getStatus().value() == 404
                    && earlyStage(database.getProvisioningStage())) {
                return;
            }
            log.debug("Provisioning reconciliation for {} will retry: {}",
                    database.getDatabaseId(), exception.getMessage());
        } catch (Exception exception) {
            log.debug("Provisioning reconciliation for {} will retry: {}",
                    database.getDatabaseId(), exception.getMessage());
        }
    }

    private boolean earlyStage(ProvisioningStage stage) {
        return stage == null || stage == ProvisioningStage.QUEUED
                || stage == ProvisioningStage.VALIDATING
                || stage == ProvisioningStage.CREATING_DATABASE;
    }

}
