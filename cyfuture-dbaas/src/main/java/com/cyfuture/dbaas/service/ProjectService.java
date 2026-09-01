package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.config.DatabaseProperties;
import com.cyfuture.dbaas.dto.CreateProjectRequest;
import com.cyfuture.dbaas.dto.ProjectResponse;
import com.cyfuture.dbaas.dto.UpdateProjectRequest;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.entity.ProjectMetadata;
import com.cyfuture.dbaas.exception.ApiException;
import com.cyfuture.dbaas.model.DatabaseStatus;
import com.cyfuture.dbaas.model.ResourceStatus;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import com.cyfuture.dbaas.repository.ProjectMetadataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProjectService {
    private final ProjectMetadataRepository projectRepository;
    private final DatabaseMetadataRepository databaseRepository;
    private final DatabaseProperties properties;


    public ProjectResponse create(CreateProjectRequest request) {
        if (projectRepository.findByProjectName(request.name()).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Project " + request.name() + " already exists");
        }

        Instant now = Instant.now();
        ProjectMetadata project = new ProjectMetadata();
        project.setProjectId("prj-" + shortId());
        project.setProjectName(request.name());
        project.setDisplayName(request.displayName());
        project.setDescription(request.description());
        project.setNamespaceName(namespaceFor(request.name()));
        project.setStatus(ResourceStatus.ACTIVE);
        project.setCreatedAt(now);
        project.setUpdatedAt(now);

        try {
            return toResponse(projectRepository.save(project));
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Project " + request.name() + " already exists");
        }
    }

    public List<ProjectResponse> list() {
        return projectRepository.findAllByOrderByCreatedAtDesc()
                .stream()
                .filter(project -> project.getStatus() == ResourceStatus.ACTIVE)
                .map(this::toResponse)
                .toList();
    }

    public ProjectResponse get(String project) {
        return toResponse(requireActiveProject(project));
    }

    public ProjectResponse update(String project, UpdateProjectRequest request) {
        ProjectMetadata metadata = requireActiveProject(project);
        metadata.setDisplayName(request.displayName());
        metadata.setDescription(request.description());
        metadata.setUpdatedAt(Instant.now());
        return toResponse(projectRepository.save(metadata));
    }

    @Transactional
    public void delete(String project) {

        ProjectMetadata metadata = projectRepository
                .findByProjectName(project)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "Project " + project + " was not found"
                ));

        if (metadata.getStatus() == ResourceStatus.DELETED
                || metadata.getStatus() == ResourceStatus.DELETING) {
            return;
        }

        List<DatabaseMetadata> databases =
                databaseRepository.findByProjectNameOrderByCreatedAtDesc(project);

        List<DatabaseMetadata> activeDatabases = databases.stream()
                .filter(database ->
                        database.getStatus() != DatabaseStatus.DELETED)
                .toList();

        if (!activeDatabases.isEmpty()) {

            DatabaseMetadata blocker = activeDatabases.get(0);

            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Project cannot be deleted while database "
                            + blocker.getDatabaseId()
                            + " is "
                            + blocker.getStatus()
            );
        }

        /*
         * UX deletion completes here.
         * Kubernetes cleanup happens asynchronously.
         */
        metadata.setStatus(ResourceStatus.DELETING);
        metadata.setUpdatedAt(Instant.now());

        projectRepository.save(metadata);
    }

    public ProjectMetadata requireActiveProject(String project) {
        ProjectMetadata metadata = projectRepository
                .findByProjectName(project)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Project " + project + " was not found"));
        if (metadata.getStatus() != ResourceStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Project " + project + " is not active");
        }
        return metadata;
    }

    private String namespaceFor(String project) {
        String namespace = properties.getNamespacePrefix() + project;
        if (namespace.length() <= 63) return namespace;
        String suffix = sha256(project).substring(0, 8);
        return namespace.substring(0, 54).replaceAll("-$", "") + "-" + suffix;
    }

    private String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private ProjectResponse toResponse(ProjectMetadata metadata) {
        return new ProjectResponse(
                metadata.getProjectId(),
                metadata.getProjectName(),
                metadata.getDisplayName(),
                metadata.getDescription(),
                metadata.getNamespaceName(),
                metadata.getStatus(),
                metadata.getCreatedAt(),
                metadata.getUpdatedAt()
        );
    }
}
