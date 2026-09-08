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
            // RestoreReconciler owns restored-target credentials. Keeping the
            // generic reconciler away from every restore target prevents a
            // lost target Secret from causing creation of a target-ID-named
            // empty database after a restore has completed or failed.
            if (restoreRepository.existsByRestoredDatabaseId(database.getDatabaseId())) continue;
            credentialLifecycleService.reconcile(database);
        }
    }
}
