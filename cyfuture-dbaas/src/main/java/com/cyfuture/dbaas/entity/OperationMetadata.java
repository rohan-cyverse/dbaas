package com.cyfuture.dbaas.entity;

import com.cyfuture.dbaas.model.OperationStatus;
import com.cyfuture.dbaas.model.OperationType;
import com.cyfuture.dbaas.model.ProvisioningStage;
import jakarta.persistence.Entity;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "operations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OperationMetadata {
    @Id
    @Column(length = 32)
    private String operationId;
    @Column(nullable = false, length = 32)
    private String databaseId;
    @Column(nullable = false, length = 30)
    private String projectName;
    @Enumerated(EnumType.STRING) @Column(length = 32) private OperationType type;
    @Enumerated(EnumType.STRING) @Column(length = 32) private OperationStatus status;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32)
    private ProvisioningStage provisioningStage;
    @Column(nullable = false)
    private int progress;
    @Column(length = 4000)
    private String message;
    @Column(length = 128)
    private String idempotencyKey;
    @Column(length = 64)
    private String requestHash;
    @Column(length = 63)
    private String opsRequestName;
    @Column(length = 63)
    private String componentName;
    private Integer targetReplicas;
    @Column(length = 32)
    private String targetStorageSize;
    @Column(length = 32)
    private String volumeName;
    @Column(length = 32)
    private String cpuRequest;
    @Column(length = 32)
    private String memoryRequest;
    @Column(length = 32)
    private String cpuLimit;
    @Column(length = 32)
    private String memoryLimit;
    private Instant createdAt;
    private Instant startedAt;
    private Instant completedAt;
}
