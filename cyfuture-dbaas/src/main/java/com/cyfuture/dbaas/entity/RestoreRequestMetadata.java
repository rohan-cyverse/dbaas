package com.cyfuture.dbaas.entity;

import com.cyfuture.dbaas.model.DatabaseEngine;
import com.cyfuture.dbaas.model.RestoreStatus;
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
@Table(name = "restore_requests", uniqueConstraints = {
        @UniqueConstraint(name = "uk_restore_project_backup_idempotency",
                columnNames = {"project_name", "source_backup_id", "idempotency_key"}),
        @UniqueConstraint(name = "uk_restore_target_database",
                columnNames = "restored_database_id"),
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
    @Column(length = 63)
    private String sourceKubernetesBackupName;
    @Column(length = 63)
    private String sourceBackupNamespace;
    @Column(nullable = false, length = 32)
    private String restoredDatabaseId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private DatabaseEngine engine;
    private Instant restoreTime;
    @Column(nullable = false, length = 63)
    private String kubernetesOpsRequestName;
    @Column(length = 63)
    private String kubernetesRestoreName;
    @Column(nullable = false, length = 63)
    private String kubernetesClusterName;
    @Column(length = 32)
    private String restoredDatabaseName;
    @Column(length = 255)
    private String publicHost;
    private Integer publicPort;
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
    private Instant lastObservedAt;
}
