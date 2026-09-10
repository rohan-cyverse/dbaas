package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.entity.BackupPolicyMetadata;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.entity.OperationMetadata;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import com.cyfuture.dbaas.repository.OperationMetadataRepository;
import com.cyfuture.dbaas.repository.BackupPolicyMetadataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MetadataCreationService {
    private final DatabaseMetadataRepository databaseRepository;
    private final OperationMetadataRepository operationRepository;
    private final BackupPolicyMetadataRepository backupPolicyRepository;

    /** Persists database creation and its required desired backup policy atomically. */
    @Transactional
    public void save(DatabaseMetadata database, OperationMetadata operation,
                     BackupPolicyMetadata backupPolicy) {
        databaseRepository.save(database);
        backupPolicyRepository.save(backupPolicy);
        operationRepository.save(operation);
    }
}
