package com.cyfuture.dbaas.entity;

import com.cyfuture.dbaas.model.RestoreMode;
import com.cyfuture.dbaas.model.RestoreStatus;
import com.cyfuture.dbaas.model.RestoreAccessMode;
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

@Entity
@Table(name = "restores", uniqueConstraints = {
        @UniqueConstraint(name = "uk_restore_project_source_database_idempotency",
                columnNames = {"project_name", "source_database_id", "idempotency_key"}),
        @UniqueConstraint(name = "uk_restore_ops_request",
                columnNames = "kubernetes_ops_request_name")
})
@Getter
@Setter
@NoArgsConstructor
public class RestoreRequestMetadata {
    @Id
    @Column(length = 32)
    private String restoreId;
    @Column(nullable = false, length = 32)
    private String operationId;
    @Column(nullable = false, length = 32)
    private String projectName;
    @Column(nullable = false, length = 32)
    private String sourceDatabaseId;
    @Column(nullable = false, length = 32)
    private String sourceBackupId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RestoreMode restoreMode;
    @Column(nullable = false, length = 32)
    private String restoredDatabaseId;
    @Column(length = 63)
    private String temporaryClusterName;
    @Column(length = 63)
    private String oldClusterName;
    @Column(nullable = false, length = 32)
    private String targetDatabaseName;
    private Instant restoreTime;
    @Column(nullable = false)
    private boolean temporary = true;
    private Integer expiresAfterHours;
    private Instant expiresAt;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RestoreAccessMode accessMode = RestoreAccessMode.PRIVATE;
    @Column(nullable = false, length = 63)
    private String kubernetesOpsRequestName;
    @Column(length = 63)
    private String kubernetesRestoreName;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RestoreStatus status;
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
    private Instant promotedAt;
    private Instant deletedAt;
    private Instant oldClusterDeleteAt;
    private Instant oldClusterDeletedAt;
    private Instant lastObservedAt;
    @Column(length = 32)
    private String safetyBackupId;
    @Column(nullable = false)
    private boolean dataReplacementStarted;
    @Column(nullable = false)
    private boolean rollbackAttempted;
}
