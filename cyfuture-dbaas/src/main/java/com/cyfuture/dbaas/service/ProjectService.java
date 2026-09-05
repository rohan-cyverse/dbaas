package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.config.DatabaseProperties;
import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.dto.CreateProjectRequest;
import com.cyfuture.dbaas.dto.ProjectResponse;
import com.cyfuture.dbaas.dto.UpdateProjectRequest;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.entity.ProjectMetadata;
import com.cyfuture.dbaas.exception.ApiException;
import com.cyfuture.dbaas.model.ResourceStatus;
import com.cyfuture.dbaas.model.DatabaseStatus;
import com.cyfuture.dbaas.model.DesiredState;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import com.cyfuture.dbaas.repository.ProjectMetadataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

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
    private final OrganizationService organizationService;
    private final FriendlyNameGenerator friendlyNameGenerator;
    private final DatabaseProperties properties;
    private final KubeBlocksClient kubeBlocksClient;

    public ProjectResponse create(CreateProjectRequest request) {
        String organizationId = organizationService.requireDefaultOrganization().getOrganizationId();
        Instant now = Instant.now();
        ProjectMetadata project = new ProjectMetadata();
        project.setProjectId("prj-" + shortId());
        project.setOrganizationId(organizationId);
        project.setDisplayName(blank(request.displayName()) ? friendlyNameGenerator.next() : request.displayName().trim());
        project.setDescription(request.description());
        project.setNamespaceName(namespaceFor(project.getProjectId()));
        project.setStatus(ResourceStatus.PROVISIONING);
        project.setCreatedAt(now);
        project.setUpdatedAt(now);

        try {
            return toResponse(activateNamespace(projectRepository.save(project)));
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Project already exists");
        }
    }

    public List<ProjectResponse> list() {
        return projectRepository.findByOrganizationIdOrderByCreatedAtDesc(currentOrganizationId())
                .stream().map(this::toResponse).toList();
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

    public void delete(String project) {
        ProjectMetadata metadata = requireOwnedProject(project);
        if (metadata.getStatus() == ResourceStatus.DELETED) return;
        // Mark every child before infrastructure cleanup. The metadata rows stay
        // authoritative while Kubernetes removes the project namespace.
        List<DatabaseMetadata> databases = databaseRepository
                .findByProjectNameOrderByCreatedAtDesc(project);
        databases.forEach(database -> {
            database.setDesiredState(DesiredState.DELETED);
            database.setStatus(DatabaseStatus.DELETING);
            // Project deletion is an explicit cascade request, so per-database
            // deletion protection must not keep the namespace finalizer alive.
            database.setDeletionProtection(false);
            database.setUpdatedAt(Instant.now());
            databaseRepository.save(database);
        });
        // Persist the desired state before the Kubernetes request. If that request
        // is temporarily unavailable, a repeated DELETE retries the same namespace.
        metadata.setStatus(ResourceStatus.DELETING);
        metadata.setUpdatedAt(Instant.now());
        projectRepository.save(metadata);
        advanceDeletion(metadata, databases);
    }

    /** Continues an asynchronous project deletion without revalidating user input. */
    void reconcileDeletion(ProjectMetadata metadata) {
        if (metadata.getStatus() != ResourceStatus.DELETING) return;
        advanceDeletion(metadata, databaseRepository
                .findByProjectNameOrderByCreatedAtDesc(metadata.getProjectId()));
    }

    private void advanceDeletion(ProjectMetadata metadata, List<DatabaseMetadata> databases) {
        for (DatabaseMetadata database : databases) {
            kubeBlocksClient.prepareProjectDatabaseDeletion(
                    database.getNamespaceName(), database.getDatabaseId());
        }
        boolean clustersGone = databases.stream().allMatch(database -> !kubeBlocksClient
                .observeCluster(database.getNamespaceName(), database.getDatabaseId()).exists());
        if (!clustersGone) return;

        kubeBlocksClient.deleteProjectNamespace(metadata.getNamespaceName(), metadata.getProjectId());
        if (!kubeBlocksClient.projectNamespaceExists(
                metadata.getNamespaceName(), metadata.getProjectId())) {
            metadata.setStatus(ResourceStatus.DELETED);
            metadata.setUpdatedAt(Instant.now());
            projectRepository.save(metadata);
        }
    }

    public ProjectMetadata requireActiveProject(String project) {
        ProjectMetadata metadata = requireOwnedProject(project);
        if (metadata.getStatus() == ResourceStatus.PROVISIONING) {
            metadata = activateNamespace(metadata);
        }
        if (metadata.getStatus() != ResourceStatus.ACTIVE) {
            if (metadata.getStatus() == ResourceStatus.DELETING) {
                throw new ApiException(HttpStatus.CONFLICT, "PROJECT_DELETION_IN_PROGRESS", false,
                        "Project " + project + " is being deleted");
            }
            throw new ApiException(HttpStatus.CONFLICT,
                    "Project " + project + " is not active");
        }
        return metadata;
    }

    private ProjectMetadata requireOwnedProject(String project) {
        String organizationId = currentOrganizationId();
        ProjectMetadata metadata = projectRepository
                .findById(project)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Project " + project + " was not found"));
        if (!organizationId.equals(metadata.getOrganizationId())) {
            // Do not disclose whether another organization's immutable project ID exists.
            throw new ApiException(HttpStatus.NOT_FOUND,
                    "Project " + project + " was not found");
        }
        return metadata;
    }

    private ProjectMetadata activateNamespace(ProjectMetadata project) {
        kubeBlocksClient.ensureProjectNamespace(project.getNamespaceName(), project.getProjectId());
        project.setStatus(ResourceStatus.ACTIVE);
        project.setUpdatedAt(Instant.now());
        return projectRepository.save(project);
    }

    private String currentOrganizationId() {
        return organizationService.requireDefaultOrganization().getOrganizationId();
    }

    private String namespaceFor(String project) {
        String prefix = properties.getNamespacePrefix();
        if (prefix == null || prefix.isBlank()) prefix = "dbaas-p-";
        if (!prefix.endsWith("-")) prefix += "-";
        String namespace = prefix + project;
        if (namespace.length() <= 63) return namespace;
        String suffix = sha256(project).substring(0, 8);
        return namespace.substring(0, 54).replaceAll("-$", "") + "-" + suffix;
    }

    private String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
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
                metadata.getOrganizationId(),
                metadata.getDisplayName(),
                metadata.getDescription(),
                metadata.getStatus(),
                metadata.getCreatedAt(),
                metadata.getUpdatedAt()
        );
    }
}
