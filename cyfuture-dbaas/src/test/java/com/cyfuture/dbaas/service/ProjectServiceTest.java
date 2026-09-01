package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.config.DatabaseProperties;
import com.cyfuture.dbaas.dto.CreateProjectRequest;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.entity.ProjectMetadata;
import com.cyfuture.dbaas.exception.ApiException;
import com.cyfuture.dbaas.model.DatabaseStatus;
import com.cyfuture.dbaas.model.ResourceStatus;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import com.cyfuture.dbaas.repository.ProjectMetadataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectServiceTest {
    private ProjectMetadataRepository projectRepository;
    private DatabaseMetadataRepository databaseRepository;
    private ProjectService service;
    private DatabaseProperties properties;

    @BeforeEach
    void setUp() {
        projectRepository = mock(ProjectMetadataRepository.class);
        databaseRepository = mock(DatabaseMetadataRepository.class);
        properties = mock(DatabaseProperties.class);
        service = new ProjectService(
                projectRepository,
                databaseRepository,
                properties
        );
        when(properties.getNamespacePrefix()).thenReturn("dbaas-");
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
        when(databaseRepository.findByProjectNameOrderByCreatedAtDesc("orders"))
                .thenReturn(List.of(database(DatabaseStatus.DELETING)));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.delete("orders"));
        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
    }

    @Test
    void projectDeletionMarksProjectDeletingAfterDatabasesAreDeleted() {
        ProjectMetadata project = new ProjectMetadata();
        project.setProjectName("orders");
        project.setStatus(ResourceStatus.ACTIVE);
        DatabaseMetadata deleted = database(DatabaseStatus.DELETED);
        when(projectRepository.findByProjectName("orders")).thenReturn(Optional.of(project));
        when(databaseRepository.findByProjectNameOrderByCreatedAtDesc("orders"))
                .thenReturn(List.of(deleted));

        service.delete("orders");

        assertEquals(ResourceStatus.DELETING, project.getStatus());
        verify(projectRepository).save(project);
    }

    private DatabaseMetadata database(DatabaseStatus status) {
        DatabaseMetadata database = new DatabaseMetadata();
        database.setDatabaseId("db-orders0001");
        database.setStatus(status);
        return database;
    }
}
