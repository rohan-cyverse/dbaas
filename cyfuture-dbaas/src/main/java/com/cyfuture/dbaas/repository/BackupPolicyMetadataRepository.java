package com.cyfuture.dbaas.repository;

import com.cyfuture.dbaas.entity.BackupPolicyMetadata;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BackupPolicyMetadataRepository extends JpaRepository<BackupPolicyMetadata, String> {
    Optional<BackupPolicyMetadata> findByProjectNameAndDatabaseId(String projectName, String databaseId);
}
