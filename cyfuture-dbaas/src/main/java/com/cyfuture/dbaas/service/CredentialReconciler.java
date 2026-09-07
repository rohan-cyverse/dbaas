package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import com.cyfuture.dbaas.repository.RestoreRequestMetadataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CredentialReconciler {
    private final DatabaseMetadataRepository databaseRepository;
    private final CredentialLifecycleService credentialLifecycleService;
    private final RestoreRequestMetadataRepository restoreRepository;

    @Scheduled(fixedDelayString = "${dbaas.credential-reconcile-ms:10000}")
    public void reconcile() {
        for (DatabaseMetadata database : databaseRepository.findAllByOrderByCreatedAtAsc()) {
            if (restoreRepository.existsByRestoredDatabaseIdAndStatusIn(database.getDatabaseId(),
                    java.util.List.of(com.cyfuture.dbaas.model.RestoreStatus.PENDING,
                            com.cyfuture.dbaas.model.RestoreStatus.RUNNING))) continue;
            credentialLifecycleService.reconcile(database);
        }
    }
}
