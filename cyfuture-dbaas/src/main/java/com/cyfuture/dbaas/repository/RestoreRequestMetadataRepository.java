package com.cyfuture.dbaas.repository;

import com.cyfuture.dbaas.entity.RestoreRequestMetadata;
import com.cyfuture.dbaas.model.RestoreStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface RestoreRequestMetadataRepository extends JpaRepository<RestoreRequestMetadata, String>,
        JpaSpecificationExecutor<RestoreRequestMetadata> {
    Optional<RestoreRequestMetadata> findByProjectNameAndSourceBackupIdAndIdempotencyKey(
            String projectName, String sourceBackupId, String idempotencyKey);
    Optional<RestoreRequestMetadata> findByRestoredDatabaseId(String restoredDatabaseId);
    boolean existsByRestoredDatabaseId(String restoredDatabaseId);
    Optional<RestoreRequestMetadata> findByOperationId(String operationId);
    boolean existsByProjectNameAndSourceBackupIdAndStatusIn(
            String projectName, String sourceBackupId, Collection<RestoreStatus> statuses);
    List<RestoreRequestMetadata> findByStatusInOrderByCreatedAtAsc(Collection<RestoreStatus> statuses);
    boolean existsByProjectNameAndSourceDatabaseIdAndStatusIn(
            String projectName, String sourceDatabaseId, Collection<RestoreStatus> statuses);
    boolean existsByRestoredDatabaseIdAndStatusIn(String restoredDatabaseId,
                                                  Collection<RestoreStatus> statuses);
    boolean existsByProjectNameAndStatusIn(String projectName, Collection<RestoreStatus> statuses);
}
