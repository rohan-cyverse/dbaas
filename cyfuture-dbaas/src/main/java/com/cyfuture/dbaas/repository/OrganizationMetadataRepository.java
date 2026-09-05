package com.cyfuture.dbaas.repository;

import com.cyfuture.dbaas.entity.OrganizationMetadata;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrganizationMetadataRepository extends JpaRepository<OrganizationMetadata, String> {
    List<OrganizationMetadata> findAllByOrderByCreatedAtDesc();
}
