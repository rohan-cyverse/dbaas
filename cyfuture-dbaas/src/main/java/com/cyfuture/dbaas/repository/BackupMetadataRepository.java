package com.cyfuture.dbaas.repository;

import com.cyfuture.dbaas.entity.BackupMetadata;
import com.cyfuture.dbaas.model.BackupStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BackupMetadataRepository extends JpaRepository<BackupMetadata, String>,
        JpaSpecificationExecutor<BackupMetadata> {
    List<BackupMetadata> findByProjectNameAndDatabaseIdOrderByCreatedAtDesc(String projectName, String databaseId);
    List<BackupMetadata> findByProjectNameOrderByCreatedAtDesc(String projectName);
    Optional<BackupMetadata> findByBackupIdAndProjectNameAndDatabaseId(
            String backupId, String projectName, String databaseId);
    Optional<BackupMetadata> findByProjectNameAndDatabaseIdAndIdempotencyKey(
            String projectName, String databaseId, String idempotencyKey);
    Optional<BackupMetadata> findByOperationId(String operationId);
    List<BackupMetadata> findByStatusInOrderByCreatedAtAsc(Collection<BackupStatus> statuses);
    List<BackupMetadata> findByProjectNameAndDatabaseIdAndStatusOrderByCompletedAtDesc(
            String projectName, String databaseId, BackupStatus status);
    Optional<BackupMetadata> findByProjectNameAndDatabaseIdAndKubernetesBackupName(
            String projectName, String databaseId, String kubernetesBackupName);
    boolean existsByProjectNameAndStatusIn(String projectName, Collection<BackupStatus> statuses);
    boolean existsByProjectNameAndDatabaseIdAndStatusIn(
            String projectName, String databaseId, Collection<BackupStatus> statuses);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select backup from BackupMetadata backup where backup.backupId = :backupId")
    Optional<BackupMetadata> findByBackupIdForUpdate(@Param("backupId") String backupId);
}
