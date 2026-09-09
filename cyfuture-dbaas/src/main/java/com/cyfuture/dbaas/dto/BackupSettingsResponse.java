package com.cyfuture.dbaas.dto;

import java.time.Instant;

/** Product-level backup settings and their observed status. */
public record BackupSettingsResponse(
        String databaseId,
        boolean scheduled,
        int retentionDays,
        String schedule,
        String timezone,
        boolean pitrEnabled,
        String status,
        Instant updatedAt,
        String errorCode,
        String errorMessage
) {}
