package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.config.DatabaseProperties;
import com.cyfuture.dbaas.dto.CreateProjectRequest;
import com.cyfuture.dbaas.entity.OrganizationMetadata;
import com.cyfuture.dbaas.entity.ProjectMetadata;
import com.cyfuture.dbaas.exception.ApiException;
import com.cyfuture.dbaas.model.ResourceStatus;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import com.cyfuture.dbaas.repository.ProjectMetadataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectServiceTest {
    private ProjectMetadataRepository projectRepository;
    private DatabaseMetadataRepository databaseRepository;
    private OrganizationService organizationService;
    private FriendlyNameGenerator friendlyNames;
    private ProjectService service;
    private OrganizationMetadata defaultOrganization;

    @BeforeEach
    void setUp() {
        projectRepository = mock(ProjectMetadataRepository.class);
        databaseRepository = mock(DatabaseMetadataRepository.class);
        organizationService = mock(OrganizationService.class);
        friendlyNames = mock(FriendlyNameGenerator.class);
        service = new ProjectService(projectRepository, databaseRepository, organizationService, friendlyNames,
                new DatabaseProperties());
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

        assertTrue(orders.namespace().matches("dbaas-p-prj-[a-f0-9]{12}"));
        assertTrue(billing.namespace().matches("dbaas-p-prj-[a-f0-9]{12}"));
        assertNotEquals(orders.namespace(), billing.namespace());
        assertEquals(OrganizationService.DEFAULT_ORGANIZATION_ID, orders.organizationId());
        verify(organizationService, times(2)).requireDefaultOrganization();
    }

    @Test
    void projectDeletionMarksProjectAndChildrenForCascade() {
        ProjectMetadata project = new ProjectMetadata();
        project.setProjectName("orders");
        project.setOrganizationId(OrganizationService.DEFAULT_ORGANIZATION_ID);
        project.setStatus(ResourceStatus.ACTIVE);
        when(projectRepository.findByProjectName("orders")).thenReturn(Optional.of(project));
        when(databaseRepository.findByProjectNameOrderByCreatedAtDesc("orders"))
                .thenReturn(java.util.List.of());

        service.delete("orders");
        assertEquals(ResourceStatus.DELETING, project.getStatus());
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
        project.setProjectName(project.getProjectId());
        project.setOrganizationId(OrganizationService.DEFAULT_ORGANIZATION_ID);
        project.setDisplayName("orders");
        project.setNamespaceName("dbaas-p-" + project.getProjectId());
        project.setStatus(ResourceStatus.ACTIVE);
        when(projectRepository.findByOrganizationIdOrderByCreatedAtDesc(
                OrganizationService.DEFAULT_ORGANIZATION_ID)).thenReturn(java.util.List.of(project));

        var projects = service.list();

        assertEquals(1, projects.size());
        assertEquals(project.getProjectId(), projects.get(0).projectId());
        verify(projectRepository).findByOrganizationIdOrderByCreatedAtDesc(
                OrganizationService.DEFAULT_ORGANIZATION_ID);
    }

    @Test
    void rejectsAProjectOutsideTheBackendManagedOrganization() {
        ProjectMetadata project = new ProjectMetadata();
        project.setProjectName("another-organization-project");
        project.setOrganizationId("org-abcdef123456");
        project.setStatus(ResourceStatus.ACTIVE);
        when(projectRepository.findByProjectName(project.getProjectName())).thenReturn(Optional.of(project));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.requireActiveProject(project.getProjectName()));

        assertEquals(org.springframework.http.HttpStatus.NOT_FOUND, exception.getStatus());
    }
}
