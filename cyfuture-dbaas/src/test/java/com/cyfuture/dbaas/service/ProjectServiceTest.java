package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.config.DatabaseProperties;
import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.dto.CreateProjectRequest;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.entity.OrganizationMetadata;
import com.cyfuture.dbaas.entity.ProjectMetadata;
import com.cyfuture.dbaas.exception.ApiException;
import com.cyfuture.dbaas.model.BackupStatus;
import com.cyfuture.dbaas.model.ResourceStatus;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import com.cyfuture.dbaas.repository.ProjectMetadataRepository;
import com.cyfuture.dbaas.repository.BackupMetadataRepository;
import com.cyfuture.dbaas.repository.RestoreRequestMetadataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectServiceTest {
    private ProjectMetadataRepository projectRepository;
    private DatabaseMetadataRepository databaseRepository;
    private OrganizationService organizationService;
    private FriendlyNameGenerator friendlyNames;
    private KubeBlocksClient kubeBlocksClient;
    private BackupMetadataRepository backupRepository;
    private RestoreRequestMetadataRepository restoreRepository;
    private BackupRetentionService retention;
    private ProjectService service;
    private OrganizationMetadata defaultOrganization;

    @BeforeEach
    void setUp() {
        projectRepository = mock(ProjectMetadataRepository.class);
        databaseRepository = mock(DatabaseMetadataRepository.class);
        organizationService = mock(OrganizationService.class);
        friendlyNames = mock(FriendlyNameGenerator.class);
        kubeBlocksClient = mock(KubeBlocksClient.class);
        backupRepository = mock(BackupMetadataRepository.class);
        restoreRepository = mock(RestoreRequestMetadataRepository.class);
        retention = mock(BackupRetentionService.class);
        service = new ProjectService(projectRepository, databaseRepository, organizationService, friendlyNames,
                new DatabaseProperties(), kubeBlocksClient, backupRepository, restoreRepository, retention);
        when(retention.prepareProjectBackupDeletion(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(projectRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        defaultOrganization = new OrganizationMetadata();
        defaultOrganization.setOrganizationId(OrganizationService.DEFAULT_ORGANIZATION_ID);
        defaultOrganization.setStatus(ResourceStatus.ACTIVE);
        when(organizationService.requireDefaultOrganization()).thenReturn(defaultOrganization);
    }

    @Test
    void createsProjectsWithinTheBackendManagedOrganizationUsingImmutableNamespaceIdentity() {
        var orders = service.create(new CreateProjectRequest("Orders", null));
        var billing = service.create(new CreateProjectRequest("Billing", null));

        assertTrue(orders.projectId().matches("prj-[a-f0-9]{12}"));
        assertTrue(billing.projectId().matches("prj-[a-f0-9]{12}"));
        ArgumentCaptor<ProjectMetadata> saved = ArgumentCaptor.forClass(ProjectMetadata.class);
        verify(projectRepository, times(4)).save(saved.capture());
        assertTrue(saved.getAllValues().get(0).getNamespaceName()
                .matches("dbaas-p-prj-[a-f0-9]{12}"));
        assertEquals(OrganizationService.DEFAULT_ORGANIZATION_ID,
                saved.getAllValues().get(0).getOrganizationId());
        assertEquals(OrganizationService.DEFAULT_ORGANIZATION_ID, orders.organizationId());
        assertEquals(ResourceStatus.ACTIVE, orders.status());
        assertEquals(ResourceStatus.ACTIVE, billing.status());
        verify(organizationService, times(2)).requireDefaultOrganization();
    }

    @Test
    void projectDeletionRequestsNamespaceRemovalAndCanBeRetried() {
        ProjectMetadata project = new ProjectMetadata();
        project.setProjectId("prj-orders0001");
        project.setOrganizationId(OrganizationService.DEFAULT_ORGANIZATION_ID);
        project.setNamespaceName("dbaas-p-prj-orders0001");
        project.setStatus(ResourceStatus.ACTIVE);
        DatabaseMetadata database = new DatabaseMetadata();
        database.setDatabaseId("db-orders0001");
        database.setNamespaceName("dbaas-p-prj-orders0001");
        database.setDeletionProtection(true);
        when(projectRepository.findById("prj-orders0001")).thenReturn(Optional.of(project));
        when(databaseRepository.findByProjectNameOrderByCreatedAtDesc("prj-orders0001"))
                .thenReturn(java.util.List.of(database));
        when(kubeBlocksClient.observeCluster("dbaas-p-prj-orders0001", "db-orders0001"))
                .thenReturn(KubeBlocksClient.ClusterObservation.missing(
                        "dbaas-p-prj-orders0001", "db-orders0001"));
        when(kubeBlocksClient.projectNamespaceExists(
                "dbaas-p-prj-orders0001", "prj-orders0001")).thenReturn(true);

        var response = service.delete("prj-orders0001");
        assertEquals(ResourceStatus.DELETING, project.getStatus());
        assertEquals("prj-orders0001", response.projectId());
        assertEquals(ResourceStatus.DELETING, response.status());
        assertEquals("Project deletion has been requested. Database cleanup and namespace removal are in progress.",
                response.message());
        verify(kubeBlocksClient).deleteProjectNamespace(
                "dbaas-p-prj-orders0001", "prj-orders0001");
        assertFalse(database.isDeletionProtection());
        verify(kubeBlocksClient).prepareProjectDatabaseDeletion(
                "dbaas-p-prj-orders0001", "db-orders0001");

        service.delete("prj-orders0001");
        verify(kubeBlocksClient, times(2)).deleteProjectNamespace(
                "dbaas-p-prj-orders0001", "prj-orders0001");
        verify(kubeBlocksClient, times(2)).prepareProjectDatabaseDeletion(
                "dbaas-p-prj-orders0001", "db-orders0001");
    }

    @Test
    void projectDeletionWaitsForClusterFinalizersBeforeDeletingNamespace() {
        ProjectMetadata project = new ProjectMetadata();
        project.setProjectId("prj-orders0001");
        project.setOrganizationId(OrganizationService.DEFAULT_ORGANIZATION_ID);
        project.setNamespaceName("dbaas-p-prj-orders0001");
        project.setStatus(ResourceStatus.ACTIVE);
        DatabaseMetadata database = new DatabaseMetadata();
        database.setDatabaseId("db-orders0001");
        database.setNamespaceName("dbaas-p-prj-orders0001");
        when(projectRepository.findById(project.getProjectId())).thenReturn(Optional.of(project));
        when(databaseRepository.findByProjectNameOrderByCreatedAtDesc(project.getProjectId()))
                .thenReturn(java.util.List.of(database));
        when(kubeBlocksClient.observeCluster(database.getNamespaceName(), database.getDatabaseId()))
                .thenReturn(new KubeBlocksClient.ClusterObservation(true, database.getNamespaceName(),
                        database.getDatabaseId(), "Deleting", 0, 1, false, "finalizing"));

        service.delete(project.getProjectId());

        verify(kubeBlocksClient).prepareProjectDatabaseDeletion(
                database.getNamespaceName(), database.getDatabaseId());
        verify(kubeBlocksClient, never()).deleteProjectNamespace(
                project.getNamespaceName(), project.getProjectId());
    }

    @Test
    void deletionReconciliationMarksProjectDeletedAfterNamespaceIsGone() {
        ProjectMetadata project = new ProjectMetadata();
        project.setProjectId("prj-orders0001");
        project.setNamespaceName("dbaas-p-prj-orders0001");
        project.setStatus(ResourceStatus.DELETING);
        when(databaseRepository.findByProjectNameOrderByCreatedAtDesc(project.getProjectId()))
                .thenReturn(java.util.List.of());
        when(kubeBlocksClient.projectNamespaceExists(
                project.getNamespaceName(), project.getProjectId())).thenReturn(false);

        service.reconcileDeletion(project);

        assertEquals(ResourceStatus.DELETED, project.getStatus());
        verify(kubeBlocksClient).deleteProjectNamespace(
                project.getNamespaceName(), project.getProjectId());
    }

    @Test
    void deletionReconciliationDeletesKnownBackupsThroughTheUnifiedPath() {
        ProjectMetadata project = new ProjectMetadata();
        project.setProjectId("prj-orders0001");
        project.setNamespaceName("dbaas-p-prj-orders0001");
        project.setStatus(ResourceStatus.DELETING);
        when(databaseRepository.findByProjectNameOrderByCreatedAtDesc(project.getProjectId()))
                .thenReturn(java.util.List.of());
        when(backupRepository.existsByProjectNameAndStatusIn(project.getProjectId(),
                java.util.List.of(BackupStatus.COMPLETED, BackupStatus.FAILED))).thenReturn(true);

        service.reconcileDeletion(project);

        verify(retention).prepareProjectBackupDeletion(project.getProjectId());
        verify(kubeBlocksClient, never()).deleteProjectNamespace(any(), any());
    }

    @Test
    void generatesFriendlyProjectDisplayNameWhenOmitted() {
        when(friendlyNames.next()).thenReturn("amber-river");

        var project = service.create(new CreateProjectRequest(null, "Development project"));

        assertEquals("amber-river", project.displayName());
    }

    @Test
    void listsOnlyProjectsOwnedByTheBackendManagedOrganization() {
        ProjectMetadata project = new ProjectMetadata();
        project.setProjectId("prj-123456789abc");
        project.setOrganizationId(OrganizationService.DEFAULT_ORGANIZATION_ID);
        project.setDisplayName("orders");
        project.setNamespaceName("dbaas-p-" + project.getProjectId());
        project.setStatus(ResourceStatus.ACTIVE);
        when(projectRepository.findByOrganizationIdOrderByCreatedAtDesc(
                OrganizationService.DEFAULT_ORGANIZATION_ID)).thenReturn(java.util.List.of(project));

        var projects = service.list();

        assertEquals(1, projects.size());
        assertEquals(project.getProjectId(), projects.get(0).projectId());
        assertEquals(OrganizationService.DEFAULT_ORGANIZATION_ID, projects.get(0).organizationId());
        verify(projectRepository).findByOrganizationIdOrderByCreatedAtDesc(
                OrganizationService.DEFAULT_ORGANIZATION_ID);
    }

    @Test
    void rejectsAProjectOutsideTheBackendManagedOrganization() {
        ProjectMetadata project = new ProjectMetadata();
        project.setProjectId("prj-another0001");
        project.setOrganizationId("org-abcdef123456");
        project.setStatus(ResourceStatus.ACTIVE);
        when(projectRepository.findById(project.getProjectId())).thenReturn(Optional.of(project));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.requireActiveProject(project.getProjectId()));

        assertEquals(org.springframework.http.HttpStatus.NOT_FOUND, exception.getStatus());
        assertEquals("PROJECT_NOT_FOUND", exception.getCode());
        assertEquals("Project was not found. Use the projectId returned by POST /api/v1/projects; "
                + "displayName is not a project identifier.", exception.getMessage());
    }

    @Test
    void activatesAnExistingProvisioningProjectBeforeItIsUsed() {
        ProjectMetadata project = new ProjectMetadata();
        project.setProjectId("prj-orders0001");
        project.setOrganizationId(OrganizationService.DEFAULT_ORGANIZATION_ID);
        project.setNamespaceName("dbaas-p-prj-orders0001");
        project.setStatus(ResourceStatus.PROVISIONING);
        when(projectRepository.findById(project.getProjectId())).thenReturn(Optional.of(project));

        ProjectMetadata active = service.requireActiveProject(project.getProjectId());

        assertEquals(ResourceStatus.ACTIVE, active.getStatus());
        verify(kubeBlocksClient).ensureProjectNamespace(
                "dbaas-p-prj-orders0001", "prj-orders0001");
    }

    @Test
    void explainsWhyADeletingProjectCannotAcceptDatabaseRequests() {
        ProjectMetadata project = new ProjectMetadata();
        project.setProjectId("prj-orders0001");
        project.setOrganizationId(OrganizationService.DEFAULT_ORGANIZATION_ID);
        project.setStatus(ResourceStatus.DELETING);
        when(projectRepository.findById(project.getProjectId())).thenReturn(Optional.of(project));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.requireActiveProject(project.getProjectId()));

        assertEquals("PROJECT_DELETION_IN_PROGRESS", exception.getCode());
    }
}
