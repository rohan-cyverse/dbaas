package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.config.DatabaseProperties;
import com.cyfuture.dbaas.dto.CreateProjectRequest;
import com.cyfuture.dbaas.entity.ProjectMetadata;
import com.cyfuture.dbaas.exception.ApiException;
import com.cyfuture.dbaas.model.ResourceStatus;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import com.cyfuture.dbaas.repository.ProjectMetadataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectServiceTest {
    private ProjectMetadataRepository projectRepository;
    private DatabaseMetadataRepository databaseRepository;
    private OrganizationService organizationService;
    private ProjectService service;

    @BeforeEach
    void setUp() {
        projectRepository = mock(ProjectMetadataRepository.class);
        databaseRepository = mock(DatabaseMetadataRepository.class);
        organizationService = mock(OrganizationService.class);
        service = new ProjectService(projectRepository, databaseRepository, organizationService,
                new DatabaseProperties());
        when(projectRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createsProjectsWithinAnOrganizationUsingImmutableNamespaceIdentity() {
        var orders = service.create("org-amber001", new CreateProjectRequest("orders", "Orders", null));
        var billing = service.create("org-amber001", new CreateProjectRequest("billing", "Billing", null));

        assertTrue(orders.namespace().matches("dbaas-p-prj-[a-f0-9]{12}"));
        assertTrue(billing.namespace().matches("dbaas-p-prj-[a-f0-9]{12}"));
        assertNotEquals(orders.namespace(), billing.namespace());
        assertEquals("org-amber001", orders.organizationId());
    }

    @Test
    void projectDeletionMarksProjectAndChildrenForCascade() {
        ProjectMetadata project = new ProjectMetadata();
        project.setProjectName("orders");
        project.setStatus(ResourceStatus.ACTIVE);
        when(projectRepository.findByProjectName("orders")).thenReturn(Optional.of(project));
        when(databaseRepository.findByProjectNameOrderByCreatedAtDesc("orders"))
                .thenReturn(java.util.List.of());

        service.delete("orders");
        assertEquals(ResourceStatus.DELETING, project.getStatus());
    }
}
