package com.cyfuture.dbaas.controller;

import com.cyfuture.dbaas.dto.OrganizationResponse;
import com.cyfuture.dbaas.dto.UpdateOrganizationRequest;
import com.cyfuture.dbaas.service.OrganizationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organization")
@RequiredArgsConstructor
@Tag(name = "Organization", description = "Backend-managed logical DBaaS tenant boundary")
public class OrganizationController {
    private final OrganizationService organizationService;

    @GetMapping
    @Operation(summary = "Get the backend-managed organization and its immutable ID")
    public OrganizationResponse get() {
        return organizationService.getDefault();
    }

    @PutMapping
    @Operation(summary = "Update the organization display name and description")
    public OrganizationResponse update(@Valid @RequestBody UpdateOrganizationRequest request) {
        return organizationService.updateDefault(request);
    }
}
