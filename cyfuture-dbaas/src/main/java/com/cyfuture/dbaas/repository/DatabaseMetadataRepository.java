package com.cyfuture.dbaas.repository;

import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.model.DatabaseStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DatabaseMetadataRepository extends JpaRepository<DatabaseMetadata, String> {
    List<DatabaseMetadata> findByProjectNameOrderByCreatedAtDesc(String projectName);
    Optional<DatabaseMetadata> findByDatabaseIdAndProjectName(String databaseId, String projectName);
    Optional<DatabaseMetadata> findByProjectNameAndIdempotencyKey(
            String projectName, String idempotencyKey);
    boolean existsByProjectName(String projectName);
    List<DatabaseMetadata> findAllByOrderByCreatedAtAsc();
    List<DatabaseMetadata> findByStatusOrderByCreatedAtAsc(DatabaseStatus status);
    boolean existsByPublicPort(Integer publicPort);
    List<DatabaseMetadata> findByPublicPortIsNotNullOrderByPublicPortAsc();
}
