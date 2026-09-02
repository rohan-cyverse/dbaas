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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectServiceTest {
    private ProjectMetadataRepository projectRepository;
    private DatabaseMetadataRepository databaseRepository;
    private ProjectService service;

    @BeforeEach
    void setUp() {
        projectRepository = mock(ProjectMetadataRepository.class);
        databaseRepository = mock(DatabaseMetadataRepository.class);
        service = new ProjectService(projectRepository, databaseRepository,
                new DatabaseProperties());
        when(projectRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createsMultipleProjectsWithoutOrganizationScope() {
        var orders = service.create(new CreateProjectRequest("orders", "Orders", null));
        var billing = service.create(new CreateProjectRequest("billing", "Billing", null));

        assertEquals("dbaas-orders", orders.namespace());
        assertEquals("dbaas-billing", billing.namespace());
    }

    @Test
    void projectDeletionIsBlockedWhileDatabasesExist() {
        ProjectMetadata project = new ProjectMetadata();
        project.setProjectName("orders");
        project.setStatus(ResourceStatus.ACTIVE);
        when(projectRepository.findByProjectName("orders")).thenReturn(Optional.of(project));
        when(databaseRepository.existsByProjectName("orders")).thenReturn(true);

        ApiException exception = assertThrows(ApiException.class,
                () -> service.delete("orders"));
        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
    }
}
