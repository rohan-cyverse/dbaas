package com.cyfuture.dbaas.repository;

import com.cyfuture.dbaas.entity.ProjectMetadata;
import com.cyfuture.dbaas.model.ResourceStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProjectMetadataRepository extends JpaRepository<ProjectMetadata, String> {
    List<ProjectMetadata> findAllByOrderByCreatedAtDesc();
    List<ProjectMetadata> findByOrganizationIdOrderByCreatedAtDesc(String organizationId);
    List<ProjectMetadata> findByStatusOrderByCreatedAtAsc(ResourceStatus status);
}
