package com.cyfuture.dbaas.dto;

import com.cyfuture.dbaas.model.BackupPolicyStatus;
import com.cyfuture.dbaas.model.BackupRetentionPolicy;
import com.cyfuture.dbaas.model.DatabaseEngine;
import com.cyfuture.dbaas.model.PitrStatus;

import java.time.Instant;

/** Safe policy state; deliberately excludes repository credentials, Secrets and Kubernetes identities. */
public record BackupPolicyResponse(
        String policyId,
        String project,
        String databaseId,
        DatabaseEngine engine,
        String repository,
        boolean autoBackupEnabled,
        int retentionDays,
        String cronExpression,
        String timezone,
        BackupRetentionPolicy retentionPolicy,
        boolean pitrEnabled,
        String method,
        BackupPolicyStatus status,
        String message,
        Instant updatedAt,
        String continuousMethod,
        PitrStatus pitrStatus,
        Instant recoverableFrom,
        Instant recoverableUntil,
        String pitrMessage,
        Instant pitrObservedAt
) {
    /** Compatibility constructor for the pre-PITR public response shape. */
    public BackupPolicyResponse(String policyId, String project, String databaseId, DatabaseEngine engine,
                                String repository, boolean autoBackupEnabled, int retentionDays,
                                String cronExpression, String timezone, BackupRetentionPolicy retentionPolicy,
                                boolean pitrEnabled, String method, BackupPolicyStatus status,
                                String message, Instant updatedAt) {
        this(policyId, project, databaseId, engine, repository, autoBackupEnabled, retentionDays,
                cronExpression, timezone, retentionPolicy, pitrEnabled, method, status, message, updatedAt,
                null, PitrStatus.DISABLED, null, null, null, null);
    }
}
