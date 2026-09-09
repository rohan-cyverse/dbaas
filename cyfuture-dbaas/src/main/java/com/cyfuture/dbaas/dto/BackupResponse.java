package com.cyfuture.dbaas.dto;

import com.cyfuture.dbaas.model.BackupStatus;
import com.cyfuture.dbaas.model.BackupTriggerMethod;
import com.cyfuture.dbaas.model.BackupType;

import java.time.Instant;

/** Product-level backup history. Infrastructure identifiers stay private. */
public record BackupResponse(
        String backupId,
        BackupType type,
        BackupTriggerMethod trigger,
        BackupStatus status,
        Long sizeBytes,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        Instant expiresAt,
        Instant deletedAt,
        String errorCode,
        String errorMessage
) {}
