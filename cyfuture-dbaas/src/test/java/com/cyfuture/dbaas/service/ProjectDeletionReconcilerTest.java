package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.entity.OperationMetadata;
import com.cyfuture.dbaas.entity.ProjectMetadata;
import com.cyfuture.dbaas.exception.ApiException;
import com.cyfuture.dbaas.model.OperationStatus;
import com.cyfuture.dbaas.model.OperationType;
import com.cyfuture.dbaas.model.ResourceStatus;
import com.cyfuture.dbaas.repository.OperationMetadataRepository;
import com.cyfuture.dbaas.repository.ProjectMetadataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectDeletionReconcilerTest {
    private ProjectMetadataRepository projectRepository;
    private OperationMetadataRepository operationRepository;
    private KubeBlocksClient kubeBlocksClient;
    private DataSource dataSource;
    private ProjectDeletionReconciler reconciler;

    @BeforeEach
    void setUp() throws Exception {
        projectRepository = mock(ProjectMetadataRepository.class);
        operationRepository = mock(OperationMetadataRepository.class);
        kubeBlocksClient = mock(KubeBlocksClient.class);
        dataSource = mock(DataSource.class);
        allowLock();
        when(operationRepository.findByDatabaseIdAndProjectNameAndType(
                ProjectService.PROJECT_OPERATION_DATABASE_ID, "orders", OperationType.DELETE))
                .thenReturn(Optional.of(operation()));
        reconciler = new ProjectDeletionReconciler(projectRepository, operationRepository,
                kubeBlocksClient, dataSource);
    }

    @Test
    void requestsNamespaceDeletionForDeletingProjects() {
        ProjectMetadata project = project();
        when(projectRepository.findByStatusOrderByCreatedAtAsc(ResourceStatus.DELETING))
                .thenReturn(List.of(project));
        when(kubeBlocksClient.observeNamespaceDeletion("dbaas-orders", "orders"))
                .thenReturn(new KubeBlocksClient.NamespaceDeletionObservation(
                        true, false, true, List.of(), List.of(), "empty"));

        reconciler.reconcile();

        verify(kubeBlocksClient).requestNamespaceDelete("dbaas-orders");
        verify(projectRepository, never()).save(project);
    }

    @Test
    void marksProjectDeletedWhenNamespaceIsAlreadyGone() {
        ProjectMetadata project = project();
        when(projectRepository.findByStatusOrderByCreatedAtAsc(ResourceStatus.DELETING))
                .thenReturn(List.of(project));
        when(kubeBlocksClient.observeNamespaceDeletion("dbaas-orders", "orders"))
                .thenReturn(new KubeBlocksClient.NamespaceDeletionObservation(
                        false, true, false, List.of(), List.of(), "Namespace is absent"));

        reconciler.reconcile();

        assertEquals(ResourceStatus.DELETED, project.getStatus());
        verify(projectRepository).save(project);
        verify(kubeBlocksClient, never()).requestNamespaceDelete("dbaas-orders");
    }

    @Test
    void leavesProjectDeletingWhenNamespaceDeleteFails() {
        ProjectMetadata project = project();
        when(projectRepository.findByStatusOrderByCreatedAtAsc(ResourceStatus.DELETING))
                .thenReturn(List.of(project));
        when(kubeBlocksClient.observeNamespaceDeletion("dbaas-orders", "orders"))
                .thenReturn(new KubeBlocksClient.NamespaceDeletionObservation(
                        true, false, true, List.of(), List.of(), "empty"));
        doThrow(new ApiException(HttpStatus.BAD_GATEWAY, "Kubernetes timeout"))
                .when(kubeBlocksClient).requestNamespaceDelete("dbaas-orders");

        reconciler.reconcile();

        assertEquals(ResourceStatus.DELETING, project.getStatus());
        verify(projectRepository, never()).save(project);
    }

    @Test
    void reportsRetainedResourcesInsteadOfDeletingNamespace() {
        ProjectMetadata project = project();
        when(projectRepository.findByStatusOrderByCreatedAtAsc(ResourceStatus.DELETING))
                .thenReturn(List.of(project));
        when(kubeBlocksClient.observeNamespaceDeletion("dbaas-orders", "orders"))
                .thenReturn(new KubeBlocksClient.NamespaceDeletionObservation(
                        true, false, false,
                        List.of("PersistentVolumeClaim/data-db-orders0001-0"),
                        List.of("PersistentVolumeClaim/data-db-orders0001-0:kubernetes.io/pvc-protection"),
                        "Namespace deletion is blocked by retained storage"));

        reconciler.reconcile();

        verify(kubeBlocksClient, never()).requestNamespaceDelete("dbaas-orders");
        assertEquals(ResourceStatus.DELETING, project.getStatus());
        verify(projectRepository).save(project);
    }

    @Test
    void truncatesLongNamespaceBlockerMessageBeforeSavingProject() {
        ProjectMetadata project = project();
        String longMessage = "Namespace deletion is blocked by resources: "
                + "PersistentVolumeClaim/data-db-orders0001-0, ".repeat(200);
        when(projectRepository.findByStatusOrderByCreatedAtAsc(ResourceStatus.DELETING))
                .thenReturn(List.of(project));
        when(kubeBlocksClient.observeNamespaceDeletion("dbaas-orders", "orders"))
                .thenReturn(new KubeBlocksClient.NamespaceDeletionObservation(
                        true, false, false, List.of("PersistentVolumeClaim/data-db-orders0001-0"),
                        List.of(), longMessage));

        reconciler.reconcile();

        assertTrue(project.getMessage().length() <= 240);
        assertTrue(project.getMessage().endsWith("[truncated]"));
        verify(projectRepository).save(project);
    }

    private ProjectMetadata project() {
        ProjectMetadata project = new ProjectMetadata();
        project.setProjectId("prj-orders0001");
        project.setProjectName("orders");
        project.setDisplayName("Orders");
        project.setNamespaceName("dbaas-orders");
        project.setStatus(ResourceStatus.DELETING);
        project.setCreatedAt(Instant.now());
        project.setUpdatedAt(Instant.now());
        return project;
    }

    private OperationMetadata operation() {
        return OperationMetadata.builder()
                .operationId("op-project0001")
                .databaseId(ProjectService.PROJECT_OPERATION_DATABASE_ID)
                .projectName("orders")
                .type(OperationType.DELETE)
                .status(OperationStatus.RUNNING)
                .createdAt(Instant.now())
                .build();
    }

    private void allowLock() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement lockStatement = mock(PreparedStatement.class);
        PreparedStatement unlockStatement = mock(PreparedStatement.class);
        ResultSet lockResult = mock(ResultSet.class);
        ResultSet unlockResult = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT GET_LOCK(?, 0)"))
                .thenReturn(lockStatement);
        when(connection.prepareStatement("SELECT RELEASE_LOCK(?)"))
                .thenReturn(unlockStatement);
        when(lockStatement.executeQuery()).thenReturn(lockResult);
        when(unlockStatement.executeQuery()).thenReturn(unlockResult);
        when(lockResult.next()).thenReturn(true);
        when(lockResult.getInt(1)).thenReturn(1);
    }
}
