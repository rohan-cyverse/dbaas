package com.cyfuture.dbaas.entity;

import com.cyfuture.dbaas.model.BackupStatus;
import com.cyfuture.dbaas.model.BackupTriggerMethod;
import com.cyfuture.dbaas.model.BackupType;
import com.cyfuture.dbaas.model.DatabaseEngine;
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

/** Minimal DBaaS correlation and history for a KubeBlocks backup. */
@Entity
@Table(name = "backups", uniqueConstraints = {
        @UniqueConstraint(name = "uk_backups_project_database_idempotency",
                columnNames = {"project_name", "database_id", "idempotency_key"}),
        @UniqueConstraint(name = "uk_backups_project_kubernetes_name",
                columnNames = {"project_name", "kubernetes_backup_name"})
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
    @Column(nullable = false, length = 32)
    private String projectName;
    @Column(nullable = false, length = 32)
    private String databaseId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private DatabaseEngine engine;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private BackupType backupType;
    @Column(nullable = false, length = 63)
    private String backupMethod;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private BackupTriggerMethod triggerMethod;
    @Column(length = 32)
    private String parentBackupId;
    @Column(length = 32)
    private String baseBackupId;
    @Column(length = 63)
    private String parentKubernetesBackupName;
    @Column(length = 63)
    private String baseKubernetesBackupName;
    @Column(nullable = false, length = 63)
    private String kubernetesBackupName;
    @Column(length = 63)
    private String kubernetesPolicyName;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private BackupStatus status;
    @Column(nullable = false, length = 32)
    private String retentionPeriod;
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

    private Instant createdAt;
    private Instant startedAt;
    private Instant completedAt;
    private Instant deleteRequestedAt;
    private Instant deletedAt;
    private Instant expiresAt;
    private Instant coverageStart;
    private Instant coverageEnd;
    private Instant lastObservedAt;
}
