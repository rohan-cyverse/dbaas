package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.dto.CreateDatabaseRequest;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.model.ProvisioningStage;
import com.cyfuture.dbaas.model.DesiredState;
import com.cyfuture.dbaas.model.DatabaseStatus;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AsyncProvisioningService {
    private final KubeBlocksClient kubeBlocksClient;
    private final DatabaseMetadataRepository databaseRepository;
    private final ProvisioningProgressService progressService;

    @Async
    public void provision(String operationId, String databaseId, String project,
                          String namespace, CreateDatabaseRequest request) {
        DatabaseMetadata database = databaseRepository
                .findByDatabaseIdAndProjectName(databaseId, project)
                .orElseThrow();

        try {
            // Re-check after the transaction boundary: delete may have won the race.
            DatabaseMetadata current = databaseRepository
                    .findByDatabaseIdAndProjectName(databaseId, project).orElse(null);
            if (current == null || current.getDesiredState() == DesiredState.DELETED
                    || current.getStatus() == DatabaseStatus.DELETING
                    || current.getStatus() == DatabaseStatus.DELETED) return;
            database = current;
            progressService.update(database, ProvisioningStage.VALIDATING, 10,
                    "Validating Kubernetes capacity and KubeBlocks configuration");
            kubeBlocksClient.preflight(namespace, project, request);
            progressService.update(database, ProvisioningStage.CREATING_DATABASE, 25,
                    "Creating the KubeBlocks database cluster");
            kubeBlocksClient.create(namespace, project, databaseId, request);
            progressService.update(database, ProvisioningStage.WAITING_FOR_REPLICAS, 40,
                    "KubeBlocks accepted the request; waiting for database replicas");
        } catch (Exception exception) {
            progressService.failed(database, safeMessage(exception));
        }
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? "Provisioning failed. Check application logs and Kubernetes events."
                : message;
    }
}
