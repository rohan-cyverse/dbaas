package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.entity.ProjectMetadata;
import com.cyfuture.dbaas.exception.ApiException;
import com.cyfuture.dbaas.model.ResourceStatus;
import com.cyfuture.dbaas.repository.ProjectMetadataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectDeletionReconcilerTest {
    private ProjectMetadataRepository projectRepository;
    private KubeBlocksClient kubeBlocksClient;
    private ProjectDeletionReconciler reconciler;

    @BeforeEach
    void setUp() {
        projectRepository = mock(ProjectMetadataRepository.class);
        kubeBlocksClient = mock(KubeBlocksClient.class);
        reconciler = new ProjectDeletionReconciler(projectRepository, kubeBlocksClient);
    }

    @Test
    void requestsNamespaceDeletionForDeletingProjects() {
        ProjectMetadata project = project();
        when(projectRepository.findByStatusOrderByCreatedAtAsc(ResourceStatus.DELETING))
                .thenReturn(List.of(project));
        when(kubeBlocksClient.namespaceExists("dbaas-orders")).thenReturn(true);

        reconciler.reconcile();

        verify(kubeBlocksClient).requestNamespaceDelete("dbaas-orders");
        verify(projectRepository, never()).save(project);
    }

    @Test
    void marksProjectDeletedWhenNamespaceIsAlreadyGone() {
        ProjectMetadata project = project();
        when(projectRepository.findByStatusOrderByCreatedAtAsc(ResourceStatus.DELETING))
                .thenReturn(List.of(project));
        when(kubeBlocksClient.namespaceExists("dbaas-orders")).thenReturn(false);

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
        when(kubeBlocksClient.namespaceExists("dbaas-orders")).thenReturn(true);
        doThrow(new ApiException(HttpStatus.BAD_GATEWAY, "Kubernetes timeout"))
                .when(kubeBlocksClient).requestNamespaceDelete("dbaas-orders");

        reconciler.reconcile();

        assertEquals(ResourceStatus.DELETING, project.getStatus());
        verify(projectRepository, never()).save(project);
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
}
