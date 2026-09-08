package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import com.cyfuture.dbaas.repository.RestoreRequestMetadataRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CredentialReconcilerTest {
    @Test
    void neverRunsGenericCredentialCreationForARestoreTarget() {
        DatabaseMetadataRepository databases = mock(DatabaseMetadataRepository.class);
        RestoreRequestMetadataRepository restores = mock(RestoreRequestMetadataRepository.class);
        CredentialLifecycleService credentials = mock(CredentialLifecycleService.class);
        CredentialReconciler reconciler = new CredentialReconciler(databases, credentials, restores);
        DatabaseMetadata target = new DatabaseMetadata();
        target.setDatabaseId("db-restored0001");

        when(databases.findAllByOrderByCreatedAtAsc()).thenReturn(List.of(target));
        when(restores.existsByRestoredDatabaseId("db-restored0001")).thenReturn(true);

        reconciler.reconcile();

        verify(credentials, never()).reconcile(target);
    }
}
