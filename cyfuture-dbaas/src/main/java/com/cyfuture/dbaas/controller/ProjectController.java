package com.cyfuture.dbaas.controller;

import com.cyfuture.dbaas.dto.CreateProjectRequest;
import com.cyfuture.dbaas.dto.OperationResponse;
import com.cyfuture.dbaas.dto.ProjectResponse;
import com.cyfuture.dbaas.dto.UpdateProjectRequest;
import com.cyfuture.dbaas.service.OperationService;
import com.cyfuture.dbaas.service.ProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
@Tag(name = "Projects", description = "Projects that contain DBaaS databases")
public class ProjectController {
    private final ProjectService projectService;
    private final OperationService operationService;

    @PostMapping
    @Operation(summary = "Create a project")
    public ResponseEntity<ProjectResponse> create(
            @Valid @RequestBody CreateProjectRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(projectService.create(request));
    }

    @GetMapping
    @Operation(summary = "List projects")
    public List<ProjectResponse> list() {
        return projectService.list();
    }

    @GetMapping("/{project}")
    @Operation(summary = "Get a project")
    public ProjectResponse get(@PathVariable String project) {
        return projectService.get(project);
    }

    @PutMapping("/{project}")
    @Operation(summary = "Update project display details")
    public ProjectResponse update(
            @PathVariable String project,
            @Valid @RequestBody UpdateProjectRequest request) {
        return projectService.update(project, request);
    }

    @DeleteMapping("/{project}")
    @Operation(summary = "Delete an empty project")
    public ResponseEntity<OperationResponse> delete(
            @PathVariable String project,
            @RequestParam(defaultValue = "false") boolean cascade) {
        if (cascade) {
            throw new com.cyfuture.dbaas.exception.ApiException(HttpStatus.BAD_REQUEST,
                    "Project cascade deletion is not enabled; delete databases first");
        }
        OperationResponse response = projectService.delete(project);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header("Location", response.statusUrl())
                .header("Operation-Location", response.statusUrl())
                .header("Retry-After", String.valueOf(response.suggestedPollingIntervalSeconds()))
                .body(response);
    }

    @GetMapping("/{project}/operations/{operationId}")
    @Operation(summary = "Get project operation status")
    public OperationResponse operation(@PathVariable String project,
                                       @PathVariable String operationId) {
        return operationService.get(project, operationId);
    }
}
