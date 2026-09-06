package com.cyfuture.dbaas.dto;

import com.cyfuture.dbaas.model.ResourceStatus;

/** Client-facing result of an asynchronous project deletion request. */
public record DeleteProjectResponse(
        String projectId,
        ResourceStatus status,
        String message
) {
}
