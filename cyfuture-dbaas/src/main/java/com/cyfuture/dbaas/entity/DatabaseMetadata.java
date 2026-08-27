package com.cyfuture.dbaas.entity;

import com.cyfuture.dbaas.model.DatabaseEngine;
import com.cyfuture.dbaas.model.DatabaseMode;
import com.cyfuture.dbaas.model.DatabaseStatus;
import com.cyfuture.dbaas.model.SizePlan;
import com.cyfuture.dbaas.model.ProvisioningStage;
import jakarta.persistence.Entity;
import jakarta.persistence.Column;
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
@Table(name = "databases", uniqueConstraints = @UniqueConstraint(
        name = "uk_database_project_idempotency",
        columnNames = {"project_name", "idempotency_key"}))
@Getter
@Setter
@NoArgsConstructor
public class DatabaseMetadata {
    @Id
    @Column(length = 32)
    private String databaseId;
    @Column(nullable = false, length = 32)
    private String operationId;
    @Column(nullable = false, length = 128)
    private String idempotencyKey;
    @Column(nullable = false, length = 64)
    private String requestHash;
    @Column(nullable = false, length = 30)
    private String projectName;
    @Column(nullable = false, length = 63)
    private String namespaceName;
    @Column(nullable = false, length = 32)
    private String displayName;
    @Column(length = 64)
    private String remark;
    @Enumerated(EnumType.STRING) @Column(length = 32) private DatabaseEngine engine;
    @Enumerated(EnumType.STRING) @Column(length = 32) private DatabaseMode mode;
    @Column(nullable = false, length = 32)
    private String databaseVersion;
    @Enumerated(EnumType.STRING) @Column(length = 32) private SizePlan sizePlan;
    private int storageGi;
    private boolean deletionProtection;
    @Enumerated(EnumType.STRING) @Column(length = 32) private DatabaseStatus status;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32)
    private ProvisioningStage provisioningStage;
    @Column(nullable = false)
    private int progress;
    private int replicas;
    private int shards;
    @Column(length = 60)
    private String timezone;
    @Column(length = 1000)
    private String allowedCidrs;
    private Integer publicPort;
    @Column(length = 2000)
    private String tags;
    @Column(length = 4000)
    private String message;
    private Instant createdAt;
    private Instant updatedAt;
}
