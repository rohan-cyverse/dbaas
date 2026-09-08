package com.cyfuture.dbaas.entity;

import com.cyfuture.dbaas.model.BackupStatus;
import com.cyfuture.dbaas.model.BackupDeletionMode;
import com.cyfuture.dbaas.model.BackupRetentionPolicy;
import com.cyfuture.dbaas.model.BackupTriggerMethod;
import com.cyfuture.dbaas.model.BackupType;
import com.cyfuture.dbaas.model.DatabaseEngine;
import com.cyfuture.dbaas.model.DatabaseMode;
import com.cyfuture.dbaas.model.SizePlan;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Desired state and a safe snapshot of source configuration for a backup. */
@Entity
@Table(name = "backups", uniqueConstraints = {
        @UniqueConstraint(name = "uk_backups_project_database_idempotency",
                columnNames = {"project_name", "database_id", "idempotency_key"}),
        @UniqueConstraint(name = "uk_backups_kubernetes_identity",
                columnNames = {"kubernetes_namespace", "kubernetes_backup_name"})
})
@Getter
@Setter
@NoArgsConstructor
public class BackupMetadata {
    @Id
    @Column(length = 32)
    private String backupId;
    @Column(nullable = false, length = 32)
    private String operationId;
    @Column(length = 32)
    private String deleteOperationId;
    @Column(nullable = false, length = 32)
    private String projectName;
    @Column(nullable = false, length = 32)
    private String databaseId;
    @Column(nullable = false, length = 32)
    private String sourceDisplayName;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private DatabaseEngine engine;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private BackupType backupType;
    /** Resolved KubeBlocks full-backup method, retained with the recovery point. */
    @Column(nullable = false, length = 63)
    private String backupMethod;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private BackupTriggerMethod triggerMethod;
    @Column(length = 32)
    private String parentBackupId;
    @Column(nullable = false, length = 32)
    private String backupChainId;
    @Column(nullable = false, length = 63)
    private String kubernetesBackupName;
    @Column(length = 63)
    private String kubernetesPolicyName;
    @Column(nullable = false, length = 63)
    private String backupRepositoryName;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private BackupStatus status;
    @Column(nullable = false, length = 32)
    private String retentionPeriod;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private BackupRetentionPolicy retentionPolicy;
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private BackupDeletionMode deletionMode;
    @Column(length = 128)
    private String deleteIdempotencyKey;
    @Column(length = 64)
    private String deleteRequestHash;
    @Column(length = 63)
    private String kubernetesNamespace;
    @Column(length = 63)
    private String kubernetesUid;
    private Long sizeBytes;
    @Column(nullable = false, length = 128)
    private String idempotencyKey;
    @Column(nullable = false, length = 64)
    private String requestHash;
    @Column(length = 64)
    private String failureCode;
    @Column(length = 1000)
    private String failureMessage;

    // These fields allow a restore after the source Cluster has been deleted.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private DatabaseMode sourceMode;
    @Column(nullable = false, length = 32)
    private String sourceDatabaseVersion;
    /** Logical application database preserved by a full backup; never a credential. */
    @Column(length = 128)
    private String sourceLogicalDatabaseName;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SizePlan sourceSizePlan;
    private int sourceStorageGi;
    private int sourceReplicas;
    private int sourceShards;
    @Column(length = 60)
    private String sourceTimezone;
    @Column(length = 1000)
    private String sourceAllowedCidrs;
    @Column(length = 2000)
    private String sourceTags;
    private Instant createdAt;
    private Instant startedAt;
    private Instant completedAt;
    private Instant deleteRequestedAt;
    private Instant deletedAt;
    private Instant expiresAt;
    private Instant purgedAt;
    private Instant lastObservedAt;
}
