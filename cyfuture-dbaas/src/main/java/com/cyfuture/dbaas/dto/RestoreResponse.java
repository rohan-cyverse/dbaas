package com.cyfuture.dbaas.dto;

import com.cyfuture.dbaas.model.RestoreMode;
import com.cyfuture.dbaas.model.RestoreStatus;
import com.cyfuture.dbaas.model.RestoreAccessMode;

import java.time.Instant;

/** Product-level restore history. Every response refers to a new database. */
public record RestoreResponse(
        String restoreId,
        String databaseId,
        String operationId,
        RestoreMode mode,
        String backupId,
        Instant restoreTime,
        String targetDatabaseName,
        boolean temporary,
        Instant expiresAt,
        RestoreAccessMode accessMode,
        RestoreStatus status,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        Instant promotedAt,
        Instant deletedAt,
        String errorCode,
        String errorMessage
) {}
