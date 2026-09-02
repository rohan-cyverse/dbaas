package com.cyfuture.dbaas.repository;

import com.cyfuture.dbaas.entity.ProjectMetadata;
import com.cyfuture.dbaas.model.ResourceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface ProjectMetadataRepository extends JpaRepository<ProjectMetadata, String> {
    Optional<ProjectMetadata> findByProjectName(String projectName);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select project from ProjectMetadata project where project.projectName = :projectName")
    Optional<ProjectMetadata> findByProjectNameForUpdate(@Param("projectName") String projectName);
    List<ProjectMetadata> findAllByOrderByCreatedAtDesc();
    List<ProjectMetadata> findByStatusOrderByCreatedAtAsc(ResourceStatus status);
}
