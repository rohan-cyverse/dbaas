package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.entity.OperationMetadata;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import com.cyfuture.dbaas.repository.OperationMetadataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MetadataCreationService {
    private final DatabaseMetadataRepository databaseRepository;
    private final OperationMetadataRepository operationRepository;

    @Transactional
    public void save(DatabaseMetadata database, OperationMetadata operation) {
        databaseRepository.save(database);
        operationRepository.save(operation);
    }
}
