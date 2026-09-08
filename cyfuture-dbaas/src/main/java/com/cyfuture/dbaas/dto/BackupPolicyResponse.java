package com.cyfuture.dbaas.dto;

import com.cyfuture.dbaas.model.BackupPolicyStatus;
import com.cyfuture.dbaas.model.BackupRetentionPolicy;
import com.cyfuture.dbaas.model.DatabaseEngine;

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
        Instant updatedAt
) {}
