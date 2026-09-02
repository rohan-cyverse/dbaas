package com.cyfuture.dbaas.repository;

import com.cyfuture.dbaas.entity.ProjectMetadata;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectMetadataRepository extends JpaRepository<ProjectMetadata, String> {
    Optional<ProjectMetadata> findByProjectName(String projectName);
    List<ProjectMetadata> findAllByOrderByCreatedAtDesc();
}
