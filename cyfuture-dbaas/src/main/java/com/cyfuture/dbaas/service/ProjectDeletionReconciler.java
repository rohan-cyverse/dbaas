package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.entity.OperationMetadata;
import com.cyfuture.dbaas.entity.ProjectMetadata;
import com.cyfuture.dbaas.model.OperationStatus;
import com.cyfuture.dbaas.model.OperationType;
import com.cyfuture.dbaas.model.ProvisioningStage;
import com.cyfuture.dbaas.model.ResourceStatus;
import com.cyfuture.dbaas.repository.OperationMetadataRepository;
import com.cyfuture.dbaas.repository.ProjectMetadataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectDeletionReconciler {
    private static final String LOCK_NAME = "cyfuture-dbaas-project-reconciler";
    private static final int PROJECT_MESSAGE_LIMIT = 240;
    private static final int OPERATION_MESSAGE_LIMIT = 3500;

    private final ProjectMetadataRepository projectRepository;
    private final OperationMetadataRepository operationRepository;
    private final KubeBlocksClient kubeBlocksClient;
    private final DataSource dataSource;

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        reconcile();
    }

    @Scheduled(fixedDelayString = "${dbaas.project-reconcile-ms:5000}")
    public void reconcile() {
        try (Connection lockConnection = dataSource.getConnection()) {
            if (!tryLock(lockConnection)) return;
            try {
                for (ProjectMetadata project :
                        projectRepository.findByStatusOrderByCreatedAtAsc(
                                ResourceStatus.DELETING)) {
                    reconcileProject(project);
                }
            } finally {
                unlock(lockConnection);
            }
        } catch (Exception exception) {
            log.warn("Project deletion reconciliation could not acquire lock: {}",
                    exception.getMessage());
        }
    }

    private void reconcileProject(ProjectMetadata project) {
        try {
            String namespace = project.getNamespaceName();
            KubeBlocksClient.NamespaceDeletionObservation observation =
                    kubeBlocksClient.observeNamespaceDeletion(namespace, project.getProjectName());
            if (observation.complete()) {
                project.setStatus(ResourceStatus.DELETED);
                project.setMessage("Project namespace is absent");
                project.setUpdatedAt(Instant.now());
                projectRepository.save(project);
                finishProjectOperation(project, OperationStatus.SUCCEEDED,
                        "Project namespace is absent");
                log.info("Project {} deletion completed", project.getProjectName());
                return;
            }

            if (!observation.safeToDelete()) {
                project.setMessage(projectMessage(observation.message()));
                project.setUpdatedAt(Instant.now());
                projectRepository.save(project);
                finishProjectOperation(project, OperationStatus.RUNNING,
                        observation.message());
                return;
            }

            kubeBlocksClient.requestNamespaceDelete(namespace);
            finishProjectOperation(project, OperationStatus.RUNNING,
                    "Namespace deletion requested; waiting for Kubernetes to remove it");
        } catch (Exception exception) {
            finishProjectOperation(project, OperationStatus.RUNNING,
                    safeMessage(exception));
            log.warn("Project {} namespace cleanup will retry: {}",
                    project.getProjectName(), exception.getMessage());
        }
    }

    private void finishProjectOperation(ProjectMetadata project, OperationStatus status,
                                        String message) {
        operationRepository.findByDatabaseIdAndProjectNameAndType(
                        ProjectService.PROJECT_OPERATION_DATABASE_ID,
                        project.getProjectName(), OperationType.DELETE)
                .ifPresent(operation -> updateOperation(operation, status, message));
    }

    private void updateOperation(OperationMetadata operation, OperationStatus status,
                                 String message) {
        operation.setStatus(status);
        operation.setProvisioningStage(status == OperationStatus.SUCCEEDED
                ? ProvisioningStage.READY : ProvisioningStage.WAITING_FOR_REPLICAS);
        operation.setProgress(status == OperationStatus.SUCCEEDED ? 100 : 50);
        operation.setMessage(operationMessage(message));
        if (operation.getStartedAt() == null) operation.setStartedAt(Instant.now());
        if (status == OperationStatus.SUCCEEDED) operation.setCompletedAt(Instant.now());
        operation.setUpdatedAt(Instant.now());
        operationRepository.save(operation);
    }

    private boolean tryLock(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT GET_LOCK(?, 0)")) {
            statement.setString(1, LOCK_NAME);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getInt(1) == 1;
            }
        }
    }

    private void unlock(Connection connection) {
        try {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT RELEASE_LOCK(?)")) {
                statement.setString(1, LOCK_NAME);
                statement.executeQuery();
            }
        } catch (Exception exception) {
            log.debug("Project deletion advisory unlock failed: {}",
                    exception.getMessage());
        }
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return "Namespace deletion will retry";
        return operationMessage(message.replaceAll("(?i)(password|passwd|pwd|token|secret)\\s*[:=]\\s*[^\\s,;\"']+", "$1=******"));
    }

    private String projectMessage(String message) {
        return truncate(message, PROJECT_MESSAGE_LIMIT);
    }

    private String operationMessage(String message) {
        return truncate(message, OPERATION_MESSAGE_LIMIT);
    }

    private String truncate(String message, int limit) {
        if (message == null || message.length() <= limit) return message;
        return message.substring(0, Math.max(0, limit - 15)) + "... [truncated]";
    }
}
