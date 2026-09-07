package com.cyfuture.dbaas.dto;

import com.cyfuture.dbaas.model.BackupStatus;
import com.cyfuture.dbaas.model.BackupType;
import com.cyfuture.dbaas.model.DatabaseEngine;

import java.time.Instant;

/** Deliberately excludes repository credentials, encryption keys, and secrets. */
public record BackupResponse(
        String backupId,
        String operationId,
        String databaseId,
        DatabaseEngine engine,
        BackupType type,
        String parentBackupId,
        String backupChainId,
        BackupStatus status,
        String retention,
        Long sizeBytes,
        String message,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt
) {}
