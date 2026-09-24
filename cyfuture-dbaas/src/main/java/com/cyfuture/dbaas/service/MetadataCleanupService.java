package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.repository.BackupMetadataRepository;
import com.cyfuture.dbaas.repository.BackupPolicyMetadataRepository;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import com.cyfuture.dbaas.repository.OperationMetadataRepository;
import com.cyfuture.dbaas.repository.ProjectMetadataRepository;
import com.cyfuture.dbaas.repository.RestoreRequestMetadataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Removes lifecycle metadata only after Kubernetes resource deletion is confirmed. */
@Service
@RequiredArgsConstructor
public class MetadataCleanupService {
    private final RestoreRequestMetadataRepository restoreRepository;
    private final BackupMetadataRepository backupRepository;
    private final BackupPolicyMetadataRepository policyRepository;
    private final OperationMetadataRepository operationRepository;
    private final DatabaseMetadataRepository databaseRepository;
    private final ProjectMetadataRepository projectRepository;

    @Transactional
    public void purgeDatabase(String project, String databaseId) {
        restoreRepository.deleteByProjectNameAndSourceDatabaseId(project, databaseId);
        backupRepository.deleteByProjectNameAndDatabaseId(project, databaseId);
        policyRepository.deleteByProjectNameAndDatabaseId(project, databaseId);
        operationRepository.deleteByDatabaseIdAndProjectName(databaseId, project);
        databaseRepository.deleteById(databaseId);
    }

    @Transactional
    public void purgeProject(String project) {
        restoreRepository.deleteByProjectName(project);
        backupRepository.deleteByProjectName(project);
        policyRepository.deleteByProjectName(project);
        operationRepository.deleteByProjectName(project);
        databaseRepository.deleteAll(databaseRepository.findByProjectNameOrderByCreatedAtDesc(project));
        projectRepository.deleteById(project);
    }
}
