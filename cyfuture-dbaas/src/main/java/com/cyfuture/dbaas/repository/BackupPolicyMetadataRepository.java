package com.cyfuture.dbaas.repository;

import com.cyfuture.dbaas.entity.BackupPolicyMetadata;
import com.cyfuture.dbaas.model.BackupPolicyStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BackupPolicyMetadataRepository extends JpaRepository<BackupPolicyMetadata, String> {
    Optional<BackupPolicyMetadata> findByProjectNameAndDatabaseId(String projectName, String databaseId);
    List<BackupPolicyMetadata> findByPolicyStatusInOrderByUpdatedAtAsc(
            Collection<BackupPolicyStatus> statuses);
    List<BackupPolicyMetadata> findByProjectNameOrderByUpdatedAtDesc(String projectName);
}
