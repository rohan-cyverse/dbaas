package com.cyfuture.dbaas.dto;

import com.cyfuture.dbaas.model.DatabaseEngine;
import com.cyfuture.dbaas.model.RestoreMode;
import com.cyfuture.dbaas.model.RestoreStatus;

import java.time.Instant;

/** Restore audit response. Passwords, usernames, Secrets and private endpoints are excluded. */
public record RestoreHistoryResponse(
        String restoreId,
        String operationId,
        String project,
        String sourceDatabaseId,
        String sourceBackupId,
        String restoredDatabaseId,
        String restoredDatabaseName,
        DatabaseEngine engine,
        RestoreStatus status,
        String publicHost,
        Integer publicPort,
        String message,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        RestoreMode restoreMode,
        Instant restoreTime,
        String baseBackupId
) {
    /** Compatibility constructor for the original full-restore history shape. */
    public RestoreHistoryResponse(String restoreId, String operationId, String project,
                                  String sourceDatabaseId, String sourceBackupId,
                                  String restoredDatabaseId, String restoredDatabaseName,
                                  DatabaseEngine engine, RestoreStatus status, String publicHost,
                                  Integer publicPort, String message, Instant createdAt,
                                  Instant startedAt, Instant completedAt) {
        this(restoreId, operationId, project, sourceDatabaseId, sourceBackupId, restoredDatabaseId,
                restoredDatabaseName, engine, status, publicHost, publicPort, message, createdAt,
                startedAt, completedAt, RestoreMode.FULL, null, sourceBackupId);
    }
}
