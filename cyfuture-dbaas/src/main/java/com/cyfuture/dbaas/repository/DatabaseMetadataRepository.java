package com.cyfuture.dbaas.repository;

import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.model.DatabaseStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface DatabaseMetadataRepository extends JpaRepository<DatabaseMetadata, String> {
    List<DatabaseMetadata> findByProjectNameOrderByCreatedAtDesc(String projectName);
    Optional<DatabaseMetadata> findByDatabaseIdAndProjectName(String databaseId, String projectName);
    boolean existsByProjectNameAndDisplayName(String projectName, String displayName);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select database from DatabaseMetadata database where database.databaseId = :databaseId and database.projectName = :projectName")
    Optional<DatabaseMetadata> findByDatabaseIdAndProjectNameForUpdate(
            @Param("databaseId") String databaseId, @Param("projectName") String projectName);
    Optional<DatabaseMetadata> findByProjectNameAndIdempotencyKey(
            String projectName, String idempotencyKey);
    boolean existsByProjectName(String projectName);
    List<DatabaseMetadata> findAllByOrderByCreatedAtAsc();
    List<DatabaseMetadata> findByStatusOrderByCreatedAtAsc(DatabaseStatus status);
    List<DatabaseMetadata> findByStatusInOrderByCreatedAtAsc(List<DatabaseStatus> statuses);
    boolean existsByPublicPort(Integer publicPort);
    List<DatabaseMetadata> findByPublicPortIsNotNullOrderByPublicPortAsc();
}
