package com.cyfuture.dbaas.controller;

import com.cyfuture.dbaas.dto.CreateOrganizationRequest;
import com.cyfuture.dbaas.dto.OrganizationResponse;
import com.cyfuture.dbaas.dto.ProjectResponse;
import com.cyfuture.dbaas.dto.UpdateOrganizationRequest;
import com.cyfuture.dbaas.dto.CreateProjectRequest;
import com.cyfuture.dbaas.service.OrganizationService;
import com.cyfuture.dbaas.service.ProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/organizations")
@RequiredArgsConstructor
@Tag(name = "Organizations", description = "Logical DBaaS tenant boundaries")
public class OrganizationController {
    private final OrganizationService organizationService;
    private final ProjectService projectService;

    @PostMapping
    @Operation(summary = "Create an organization with an optional friendly display name")
    public ResponseEntity<OrganizationResponse> create(@Valid @RequestBody CreateOrganizationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(organizationService.create(request));
    }

    @GetMapping
    public List<OrganizationResponse> list() {
        return organizationService.list();
    }

    @GetMapping("/{organizationId}")
    public OrganizationResponse get(@PathVariable String organizationId) {
        return organizationService.get(organizationId);
    }

    @PutMapping("/{organizationId}")
    public OrganizationResponse update(@PathVariable String organizationId,
                                       @Valid @RequestBody UpdateOrganizationRequest request) {
        return organizationService.update(organizationId, request);
    }

    @PostMapping("/{organizationId}/projects")
    @Operation(summary = "Create a project within an organization")
    public ResponseEntity<ProjectResponse> createProject(@PathVariable String organizationId,
                                                          @Valid @RequestBody CreateProjectRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(projectService.create(organizationId, request));
    }

    @GetMapping("/{organizationId}/projects")
    public List<ProjectResponse> listProjects(@PathVariable String organizationId) {
        return projectService.list(organizationId);
    }
}
