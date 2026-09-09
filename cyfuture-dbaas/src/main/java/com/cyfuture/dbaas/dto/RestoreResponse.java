package com.cyfuture.dbaas.dto;

import com.cyfuture.dbaas.model.RestoreMode;
import com.cyfuture.dbaas.model.RestoreStatus;

import java.time.Instant;

/** Product-level restore history. Every response refers to a new database. */
public record RestoreResponse(
        String restoreId,
        String databaseId,
        RestoreMode mode,
        String backupId,
        Instant restoreTime,
        RestoreStatus status,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        String errorCode,
        String errorMessage
) {}
