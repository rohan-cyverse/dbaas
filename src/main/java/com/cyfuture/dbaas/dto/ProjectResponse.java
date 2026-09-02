package com.cyfuture.dbaas.dto;

import com.cyfuture.dbaas.model.ResourceStatus;

import java.time.Instant;

public record ProjectResponse(
        String projectId,
        String name,
        String displayName,
        String description,
        String namespace,
        ResourceStatus status,
        Instant createdAt,
        Instant updatedAt
) {
}
