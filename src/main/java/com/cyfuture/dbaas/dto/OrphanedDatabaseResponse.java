package com.cyfuture.dbaas.dto;

import com.cyfuture.dbaas.model.DatabaseStatus;

import java.time.Instant;

public record OrphanedDatabaseResponse(
        String namespace,
        String clusterName,
        String databaseId,
        String project,
        String engine,
        DatabaseStatus status,
        String kubeblocksPhase,
        Instant observedAt,
        String message
) {}
