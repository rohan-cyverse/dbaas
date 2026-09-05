package com.cyfuture.dbaas.dto;

import com.cyfuture.dbaas.model.ResourceStatus;

import java.time.Instant;

public record ProjectResponse(
        String projectId,
        String organizationId,
        String displayName,
        String description,
        ResourceStatus status,
        Instant createdAt,
        Instant updatedAt
) {
}
