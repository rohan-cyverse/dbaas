package com.cyfuture.dbaas.entity;

import com.cyfuture.dbaas.model.BackupPolicyStatus;
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

/** Minimal desired settings and internal KubeBlocks correlation for one database. */
@Entity
@Table(name = "backup_settings", uniqueConstraints = @UniqueConstraint(
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
    @Column(length = 63)
    private String kubernetesPolicyName;
    @Column(length = 128)
    private String cronExpression;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private BackupPolicyStatus policyStatus;
    private boolean autoBackupEnabled;
    private int retentionDays;
    @Column(nullable = false, length = 60)
    private String timezone;
    private boolean pitrEnabled;
    private boolean configurationApplied;
    @Column(length = 63)
    private String kubernetesScheduleName;
    @Column(length = 32)
    private String policyUpdateOperationId;
    @Column(length = 64)
    private String failureCode;
    @Column(length = 1000)
    private String failureMessage;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant lastObservedAt;
}
