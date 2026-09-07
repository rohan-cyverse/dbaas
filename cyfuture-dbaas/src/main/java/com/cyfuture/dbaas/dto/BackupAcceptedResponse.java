package com.cyfuture.dbaas.dto;

import com.cyfuture.dbaas.model.BackupStatus;

public record BackupAcceptedResponse(
        String operationId,
        String backupId,
        BackupStatus status,
        String statusUrl,
        int pollAfterSeconds
) {}
