package com.cyfuture.dbaas.repository;

import com.cyfuture.dbaas.entity.BackupMetadata;
import com.cyfuture.dbaas.model.BackupStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BackupMetadataRepository extends JpaRepository<BackupMetadata, String> {
    List<BackupMetadata> findByProjectNameAndDatabaseIdOrderByCreatedAtDesc(String projectName, String databaseId);
    Optional<BackupMetadata> findByBackupIdAndProjectNameAndDatabaseId(
            String backupId, String projectName, String databaseId);
    Optional<BackupMetadata> findByProjectNameAndDatabaseIdAndIdempotencyKey(
            String projectName, String databaseId, String idempotencyKey);
    Optional<BackupMetadata> findByOperationId(String operationId);
    Optional<BackupMetadata> findByDeleteOperationId(String deleteOperationId);
    List<BackupMetadata> findByStatusInOrderByCreatedAtAsc(Collection<BackupStatus> statuses);
    boolean existsByProjectNameAndStatusIn(String projectName, Collection<BackupStatus> statuses);
    boolean existsByProjectNameAndDatabaseIdAndStatusIn(
            String projectName, String databaseId, Collection<BackupStatus> statuses);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select backup from BackupMetadata backup where backup.backupId = :backupId")
    Optional<BackupMetadata> findByBackupIdForUpdate(@Param("backupId") String backupId);
}
