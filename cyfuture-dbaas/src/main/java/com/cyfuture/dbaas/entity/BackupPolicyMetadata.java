package com.cyfuture.dbaas.entity;

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

/** Observed per-cluster KubeBlocks policy plus reserved schedule configuration. */
@Entity
@Table(name = "backup_policies", uniqueConstraints = @UniqueConstraint(
        name = "uk_backup_policy_project_database",
        columnNames = {"project_name", "database_id"}))
@Getter
@Setter
@NoArgsConstructor
public class BackupPolicyMetadata {
    @Id
    @Column(length = 32)
    private String policyId;
    @Column(nullable = false, length = 32)
    private String projectName;
    @Column(nullable = false, length = 32)
    private String databaseId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private DatabaseEngine engine;
    @Column(nullable = false, length = 63)
    private String kubernetesPolicyName;
    @Column(nullable = false, length = 63)
    private String backupRepositoryName;
    @Column(nullable = false, length = 63)
    private String defaultBackupMethod;
    private boolean encryptionConfigured;
    private boolean schedulingEnabled;
    @Column(length = 128)
    private String cronExpression;
    @Column(nullable = false, length = 32)
    private String defaultRetentionPeriod;
    @Column(nullable = false, length = 32)
    private String observedStatus;
    private Instant createdAt;
    private Instant updatedAt;
}
