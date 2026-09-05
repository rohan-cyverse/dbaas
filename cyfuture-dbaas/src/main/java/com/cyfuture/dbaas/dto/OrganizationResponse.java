package com.cyfuture.dbaas.dto;

import com.cyfuture.dbaas.model.ResourceStatus;

public record OrganizationResponse(
        String organizationId,
        String displayName,
        String description,
        ResourceStatus status
) {}
