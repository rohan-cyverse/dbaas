package com.cyfuture.dbaas.repository;

import com.cyfuture.dbaas.entity.OperationMetadata;
import com.cyfuture.dbaas.model.OperationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OperationMetadataRepository extends JpaRepository<OperationMetadata, String> {
    Optional<OperationMetadata> findByOperationIdAndProjectName(
            String operationId, String projectName);
    Optional<OperationMetadata> findByOperationIdAndDatabaseIdAndProjectName(
            String operationId, String databaseId, String projectName);
    List<OperationMetadata> findByDatabaseIdAndProjectNameOrderByCreatedAtDesc(
            String databaseId, String projectName);
    List<OperationMetadata> findByStatusIn(List<OperationStatus> statuses);
    Optional<OperationMetadata> findByDatabaseIdAndProjectNameAndIdempotencyKey(
            String databaseId, String projectName, String idempotencyKey);
    List<OperationMetadata> findByDatabaseIdAndProjectNameAndStatusIn(
            String databaseId, String projectName, List<OperationStatus> statuses);
}
