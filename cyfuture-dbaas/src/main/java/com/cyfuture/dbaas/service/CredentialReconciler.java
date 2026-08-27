package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CredentialReconciler {
    private final DatabaseMetadataRepository databaseRepository;
    private final CredentialLifecycleService credentialLifecycleService;

    @Scheduled(fixedDelayString = "${dbaas.credential-reconcile-ms:10000}")
    public void reconcile() {
        for (DatabaseMetadata database : databaseRepository.findAllByOrderByCreatedAtAsc()) {
            credentialLifecycleService.reconcile(database);
        }
    }
}
