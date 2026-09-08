package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.dto.BackupRepositoryResponse;
import com.cyfuture.dbaas.dto.BackupResponse;
import com.cyfuture.dbaas.dto.PageResponse;
import com.cyfuture.dbaas.dto.RestoreHistoryResponse;
import com.cyfuture.dbaas.entity.BackupMetadata;
import com.cyfuture.dbaas.entity.RestoreRequestMetadata;
import com.cyfuture.dbaas.exception.ApiException;
import com.cyfuture.dbaas.model.BackupStatus;
import com.cyfuture.dbaas.model.BackupTriggerMethod;
import com.cyfuture.dbaas.model.BackupType;
import com.cyfuture.dbaas.model.DatabaseEngine;
import com.cyfuture.dbaas.model.RestoreStatus;
import com.cyfuture.dbaas.repository.BackupMetadataRepository;
import com.cyfuture.dbaas.repository.RestoreRequestMetadataRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Project-owned backup set, backup history and restore history query service. */
@Service
@RequiredArgsConstructor
public class BackupCatalogService {
    private static final Set<String> BACKUP_SORTS = Set.of("createdAt", "completedAt", "sizeBytes", "status", "backupId");
    private static final Set<String> RESTORE_SORTS = Set.of("createdAt", "completedAt", "status", "restoreId");

    private final BackupMetadataRepository backupRepository;
    private final RestoreRequestMetadataRepository restoreRepository;
    private final ProjectService projectService;
    private final BackupService backupService;
    private final KubeBlocksClient kubeBlocksClient;

    public PageResponse<BackupResponse> backupSet(BackupQuery query) {
        if (query.status() != null && query.status() != BackupStatus.COMPLETED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BACKUP_SET_STATUS_INVALID", false,
                    "Backup Set contains only available completed backups.");
        }
        return backups(query, true);
    }

    public PageResponse<BackupResponse> backupHistory(BackupQuery query) {
        return backups(query, false);
    }

    public PageResponse<RestoreHistoryResponse> restoreHistory(RestoreQuery query) {
        Set<String> owned = enforceOwnership(query.project());
        Specification<RestoreRequestMetadata> specification = (root, ignored, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(root.get("projectName").in(owned));
            if (query.project() != null) predicates.add(builder.equal(root.get("projectName"), query.project()));
            if (query.databaseId() != null) predicates.add(builder.or(
                    builder.equal(root.get("sourceDatabaseId"), query.databaseId()),
                    builder.equal(root.get("restoredDatabaseId"), query.databaseId())));
            if (query.engine() != null) predicates.add(builder.equal(root.get("engine"), query.engine()));
            if (query.status() != null) predicates.add(builder.equal(root.get("status"), query.status()));
            if (query.from() != null) predicates.add(builder.greaterThanOrEqualTo(root.get("createdAt"), query.from()));
            if (query.to() != null) predicates.add(builder.lessThanOrEqualTo(root.get("createdAt"), query.to()));
            if (query.search() != null && !query.search().isBlank()) {
                String search = "%" + query.search().trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get("restoreId")), search),
                        builder.like(builder.lower(root.get("sourceBackupId")), search),
                        builder.like(builder.lower(root.get("restoredDatabaseName")), search),
                        builder.like(builder.lower(root.get("restoredDatabaseId")), search)));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
        Page<RestoreRequestMetadata> page = restoreRepository.findAll(specification,
                pageRequest(query.page(), query.size(), query.sort(), query.order(), RESTORE_SORTS));
        return new PageResponse<>(page.getContent().stream().map(this::restoreResponse).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    public List<BackupRepositoryResponse> repositories() {
        // Establishes current-organization ownership before exposing even safe
        // shared infrastructure metadata.
        projectService.ownedProjectIds();
        return kubeBlocksClient.listBackupRepositories();
    }

    private PageResponse<BackupResponse> backups(BackupQuery query, boolean completedOnly) {
        Set<String> owned = enforceOwnership(query.project());
        Specification<BackupMetadata> specification = (root, ignored, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(root.get("projectName").in(owned));
            if (query.project() != null) predicates.add(builder.equal(root.get("projectName"), query.project()));
            if (query.databaseId() != null) predicates.add(builder.equal(root.get("databaseId"), query.databaseId()));
            if (query.engine() != null) predicates.add(builder.equal(root.get("engine"), query.engine()));
            if (completedOnly) predicates.add(builder.equal(root.get("status"), BackupStatus.COMPLETED));
            else if (query.status() != null) predicates.add(builder.equal(root.get("status"), query.status()));
            if (query.triggerMethod() != null) {
                predicates.add(builder.equal(root.get("triggerMethod"), query.triggerMethod()));
            }
            if (query.backupType() != null) predicates.add(builder.equal(root.get("backupType"), query.backupType()));
            if (query.from() != null) predicates.add(builder.greaterThanOrEqualTo(root.get("createdAt"), query.from()));
            if (query.to() != null) predicates.add(builder.lessThanOrEqualTo(root.get("createdAt"), query.to()));
            if (query.search() != null && !query.search().isBlank()) {
                String search = "%" + query.search().trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get("backupId")), search),
                        builder.like(builder.lower(root.get("sourceDisplayName")), search),
                        builder.like(builder.lower(root.get("kubernetesBackupName")), search)));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
        Page<BackupMetadata> page = backupRepository.findAll(specification,
                pageRequest(query.page(), query.size(), query.sort(), query.order(), BACKUP_SORTS));
        return new PageResponse<>(page.getContent().stream().map(backupService::response).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    private Set<String> enforceOwnership(String project) {
        if (project != null && !project.isBlank()) {
            projectService.requireProjectOwnership(project);
            return Set.of(project);
        }
        Set<String> owned = projectService.ownedProjectIds();
        if (owned.isEmpty()) {
            // Empty IN predicates are provider-dependent; an impossible project
            // predicate guarantees no cross-organization history leakage.
            return Set.of("__no_owned_projects__");
        }
        return owned;
    }

    private PageRequest pageRequest(Integer requestedPage, Integer requestedSize, String sort,
                                    String order, Set<String> allowedSorts) {
        int page = requestedPage == null ? 0 : requestedPage;
        int size = requestedSize == null ? 20 : requestedSize;
        if (page < 0 || size < 1 || size > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE_REQUEST", false,
                    "page must be non-negative and size must be between 1 and 100.");
        }
        String property = sort == null || sort.isBlank() ? "createdAt" : sort;
        if (!allowedSorts.contains(property)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SORT", false,
                    "Unsupported sort field.");
        }
        Sort.Direction direction = "asc".equalsIgnoreCase(order) ? Sort.Direction.ASC : Sort.Direction.DESC;
        if (order != null && !order.isBlank() && !"asc".equalsIgnoreCase(order) && !"desc".equalsIgnoreCase(order)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SORT", false,
                    "order must be asc or desc.");
        }
        return PageRequest.of(page, size, Sort.by(direction, property));
    }

    private RestoreHistoryResponse restoreResponse(RestoreRequestMetadata restore) {
        String message = restore.getFailureMessage();
        if (message == null || message.isBlank()) {
            message = switch (restore.getStatus()) {
                case PENDING -> "Restore request is queued.";
                case RUNNING -> "Restore is running.";
                case COMPLETED -> "Restore completed.";
                case FAILED -> "Restore failed.";
            };
        }
        return new RestoreHistoryResponse(restore.getRestoreId(), restore.getOperationId(), restore.getProjectName(),
                restore.getSourceDatabaseId(), restore.getSourceBackupId(), restore.getRestoredDatabaseId(),
                restore.getRestoredDatabaseName(), restore.getEngine(), restore.getStatus(), restore.getPublicHost(),
                restore.getPublicPort(), message, restore.getCreatedAt(), restore.getStartedAt(),
                restore.getCompletedAt());
    }

    public record BackupQuery(String project, String databaseId, DatabaseEngine engine, BackupStatus status,
                              BackupTriggerMethod triggerMethod, BackupType backupType,
                              Instant from, Instant to, String search,
                              Integer page, Integer size, String sort, String order) {}

    public record RestoreQuery(String project, String databaseId, DatabaseEngine engine, RestoreStatus status,
                               Instant from, Instant to, String search,
                               Integer page, Integer size, String sort, String order) {}
}
