package com.cyfuture.dbaas.controller;

import com.cyfuture.dbaas.dto.CreateProjectRequest;
import com.cyfuture.dbaas.dto.ProjectResponse;
import com.cyfuture.dbaas.model.ResourceStatus;
import com.cyfuture.dbaas.service.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectControllerTest {
    @Test
    void createsProjectWithCanonicalProjectIdLocation() {
        ProjectService projectService = mock(ProjectService.class);
        ProjectController controller = new ProjectController(projectService);
        ProjectResponse project = new ProjectResponse(
                "prj-123456789abc", "Orders", "Production databases",
                ResourceStatus.ACTIVE, Instant.EPOCH, Instant.EPOCH);
        when(projectService.create(new CreateProjectRequest("Orders", "Production databases")))
                .thenReturn(project);

        var response = controller.create(new CreateProjectRequest("Orders", "Production databases"));

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("/api/v1/projects/prj-123456789abc", response.getHeaders().getLocation().toString());
        assertEquals(project, response.getBody());
    }
}
