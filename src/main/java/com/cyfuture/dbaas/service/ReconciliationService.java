package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.dto.OrphanedDatabaseResponse;
import com.cyfuture.dbaas.model.DatabaseStatus;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ReconciliationService {
    private final KubeBlocksClient kubeBlocksClient;
    private final DatabaseMetadataRepository databaseRepository;

    public List<OrphanedDatabaseResponse> orphans() {
        Set<String> metadataIds = new HashSet<>(databaseRepository.findAll()
                .stream()
                .map(database -> database.getNamespaceName() + "/" + database.getDatabaseId())
                .toList());
        Instant observedAt = Instant.now();
        return kubeBlocksClient.listManagedClusters()
                .stream()
                .filter(cluster -> !metadataIds.contains(cluster.namespace() + "/" + cluster.databaseId()))
                .map(cluster -> new OrphanedDatabaseResponse(
                        cluster.namespace(),
                        cluster.name(),
                        cluster.databaseId(),
                        cluster.project(),
                        cluster.engine(),
                        DatabaseStatus.ORPHANED,
                        cluster.phase(),
                        observedAt,
                        cluster.message()))
                .toList();
    }
}
