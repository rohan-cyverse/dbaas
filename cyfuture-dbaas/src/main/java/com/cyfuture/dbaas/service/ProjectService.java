package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.config.DatabaseProperties;
import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.dto.CreateProjectRequest;
import com.cyfuture.dbaas.dto.DeleteProjectResponse;
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
import com.cyfuture.dbaas.repository.BackupMetadataRepository;
import com.cyfuture.dbaas.repository.RestoreRequestMetadataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ProjectService {
    private static final String CURRENT_USER_HEADER = "X-Cyfuture-User";
    private static final String CURRENT_USER_FALLBACK_HEADER = "X-Current-User";
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final ProjectMetadataRepository projectRepository;
    private final DatabaseMetadataRepository databaseRepository;
    private final FriendlyNameGenerator friendlyNameGenerator;
    private final DatabaseProperties properties;
    private final KubeBlocksClient kubeBlocksClient;
    private final BackupMetadataRepository backupRepository;
    private final RestoreRequestMetadataRepository restoreRepository;
    private final BackupRetentionService backupRetentionService;

    public ProjectResponse create(CreateProjectRequest request) {
        Instant now = Instant.now();
        ProjectMetadata project = new ProjectMetadata();
        project.setProjectId("prj-" + shortId());
        project.setDisplayName(blank(request.displayName()) ? friendlyNameGenerator.next() : request.displayName().trim());
        project.setDescription(request.description());
        project.setCreatedBy(currentUser());
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
        String currentUser = currentUser();
        return (currentUser == null
                ? projectRepository.findAllByOrderByCreatedAtDesc()
                : projectRepository.findByCreatedByOrderByCreatedAtDesc(currentUser))
                .stream().map(this::toResponse).toList();
    }

    public ProjectResponse get(String project) {
        ProjectMetadata metadata = requireProject(project);
        if (metadata.getStatus() == ResourceStatus.PROVISIONING) {
            metadata = activateNamespace(metadata);
        }
        return toResponse(metadata);
    }

    public ProjectResponse update(String project, UpdateProjectRequest request) {
        ProjectMetadata metadata = requireActiveProject(project);
        metadata.setDisplayName(request.displayName());
        metadata.setDescription(request.description());
        metadata.setUpdatedAt(Instant.now());
        return toResponse(projectRepository.save(metadata));
    }

    public DeleteProjectResponse delete(String project) {
        ProjectMetadata metadata = requireProject(project);
        if (metadata.getStatus() == ResourceStatus.DELETED) return deletionResponse(metadata);
        if (metadata.getStatus() == ResourceStatus.DELETING) return deletionResponse(metadata);
        List<DatabaseMetadata> databases = databaseRepository
                .findByProjectNameOrderByCreatedAtDesc(project);
        backupRetentionService.prepareProjectBackupDeletion(project);
        // Mark every child before infrastructure cleanup. The metadata rows stay
        // authoritative while Kubernetes removes the project namespace.
        markDatabasesDeleting(databases);
        metadata.setStatus(ResourceStatus.DELETING);
        metadata.setUpdatedAt(Instant.now());
        projectRepository.save(metadata);
        return deletionResponse(metadata);
    }

    private void markDatabasesDeleting(List<DatabaseMetadata> databases) {
        databases.forEach(database -> {
            database.setDesiredState(DesiredState.DELETED);
            database.setStatus(DatabaseStatus.DELETING);
            // Project deletion is an explicit cascade request, so per-database
            // deletion protection must not keep the namespace finalizer alive.
            database.setDeletionProtection(false);
            database.setUpdatedAt(Instant.now());
            databaseRepository.save(database);
        });
    }

    /** Continues an asynchronous project deletion without revalidating user input. */
    void reconcileDeletion(ProjectMetadata metadata) {
        if (metadata.getStatus() != ResourceStatus.DELETING) return;
        backupRetentionService.prepareProjectBackupDeletion(metadata.getProjectId());
        advanceDeletion(metadata, databaseRepository
                .findByProjectNameOrderByCreatedAtDesc(metadata.getProjectId()));
    }

    private void advanceDeletion(ProjectMetadata metadata, List<DatabaseMetadata> databases) {
        for (DatabaseMetadata database : databases) {
            kubeBlocksClient.prepareProjectDatabaseDeletion(
                    database.getNamespaceName(), database.physicalClusterName());
        }
        boolean clustersGone = databases.stream().allMatch(database -> !kubeBlocksClient
                .observeCluster(database.getNamespaceName(), database.physicalClusterName()).exists());
        if (!clustersGone) return;

        kubeBlocksClient.deleteProjectNamespace(metadata.getNamespaceName(), metadata.getProjectId());
        if (!kubeBlocksClient.projectNamespaceExists(
                metadata.getNamespaceName(), metadata.getProjectId())) {
            restoreRepository.deleteByProjectName(metadata.getProjectId());
            backupRepository.deleteByProjectName(metadata.getProjectId());
            metadata.setStatus(ResourceStatus.DELETED);
            metadata.setUpdatedAt(Instant.now());
            projectRepository.save(metadata);
        }
    }

    private DeleteProjectResponse deletionResponse(ProjectMetadata metadata) {
        if (metadata.getStatus() == ResourceStatus.DELETED) {
            return new DeleteProjectResponse(metadata.getProjectId(), ResourceStatus.DELETED,
                    "Project deletion is complete.");
        }
        return new DeleteProjectResponse(metadata.getProjectId(), ResourceStatus.DELETING,
                "Project deletion has been requested. Database cleanup and namespace removal are in progress.");
    }

    public ProjectMetadata requireActiveProject(String project) {
        ProjectMetadata metadata = requireProject(project);
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

    private ProjectMetadata requireProject(String project) {
        String currentUser = currentUser();
        return (currentUser == null
                ? projectRepository.findById(project)
                : projectRepository.findByProjectIdAndCreatedBy(project, currentUser))
                .orElseThrow(this::projectNotFound);
    }

    /** Includes a project that is deleting or deleted, for immutable history lookups. */
    public ProjectMetadata requireExistingProject(String project) {
        return requireProject(project);
    }

    private ApiException projectNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", false,
                "Project was not found. Use the projectId returned by POST /api/v1/projects; "
                        + "displayName is not a project identifier.");
    }

    private String currentUser() {
        String headerUser = currentUserFromRequest();
        if (!blank(headerUser)) return emailUser(headerUser);
        return null;
    }

    private String currentUserFromRequest() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return null;
        }
        String headerUser = attributes.getRequest().getHeader(CURRENT_USER_HEADER);
        if (!blank(headerUser)) return headerUser;
        headerUser = attributes.getRequest().getHeader(CURRENT_USER_FALLBACK_HEADER);
        if (!blank(headerUser)) return headerUser;
        throw new ApiException(HttpStatus.BAD_REQUEST, "CURRENT_USER_REQUIRED", false,
                "Current user is required. Send the Cyfuture.ai email in the "
                        + CURRENT_USER_HEADER + " header.");
    }

    private String emailUser(String value) {
        String email = value.trim().toLowerCase();
        if (!EMAIL.matcher(email).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CURRENT_USER_EMAIL_REQUIRED", false,
                    "Current user must be a valid Cyfuture.ai email in the "
                            + CURRENT_USER_HEADER + " header.");
        }
        return email;
    }

    private ProjectMetadata activateNamespace(ProjectMetadata project) {
        kubeBlocksClient.ensureProjectNamespace(project.getNamespaceName(), project.getProjectId());
        project.setStatus(ResourceStatus.ACTIVE);
        project.setUpdatedAt(Instant.now());
        return projectRepository.save(project);
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
                metadata.getDisplayName(),
                metadata.getDescription(),
                metadata.getStatus(),
                metadata.getCreatedAt(),
                metadata.getUpdatedAt()
        );
    }
}
