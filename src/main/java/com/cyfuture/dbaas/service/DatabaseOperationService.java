package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.dto.HorizontalScalingRequest;
import com.cyfuture.dbaas.dto.OperationResponse;
import com.cyfuture.dbaas.dto.RestartRequest;
import com.cyfuture.dbaas.dto.StorageExpansionRequest;
import com.cyfuture.dbaas.dto.VerticalScalingRequest;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.entity.OperationMetadata;
import com.cyfuture.dbaas.exception.ApiException;
import com.cyfuture.dbaas.mapper.OperationMapper;
import com.cyfuture.dbaas.model.DatabaseStatus;
import com.cyfuture.dbaas.model.OperationStatus;
import com.cyfuture.dbaas.model.OperationType;
import com.cyfuture.dbaas.model.ProvisioningStage;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import com.cyfuture.dbaas.repository.OperationMetadataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class DatabaseOperationService {
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9._:-]{8,128}$");

    private final KubeBlocksClient kubeBlocksClient;
    private final DatabaseMetadataRepository databaseRepository;
    private final OperationMetadataRepository operationRepository;
    private final ProjectService projectService;
    private final OperationMapper operationMapper;
    private final KubeBlocksOperationSubmitter submitter;

    @Transactional
    public OperationResponse verticalScaling(String project, String databaseId,
                                             String idempotencyKey,
                                             VerticalScalingRequest request) {
        String hash = requestHash("vertical", request.componentName(),
                request.requests().cpu(), request.requests().memory(),
                request.limits().cpu(), request.limits().memory());
        OperationMetadata operation = prepare(project, databaseId, idempotencyKey,
                hash, OperationType.VERTICAL_SCALING);
        if (operation.getRequestHash() != null) return operationMapper.toResponse(operation);

        validateResources(request);
        KubeBlocksClient.ClusterComponentInfo component = kubeBlocksClient.requireComponent(
                operationDatabase(project, databaseId).getNamespaceName(), databaseId, request.componentName());
        operation.setComponentName(component.name());
        operation.setCpuRequest(request.requests().cpu());
        operation.setMemoryRequest(request.requests().memory());
        operation.setCpuLimit(request.limits().cpu());
        operation.setMemoryLimit(request.limits().memory());
        return queue(operation, hash, "Vertical scaling request queued");
    }

    @Transactional
    public OperationResponse horizontalScaling(String project, String databaseId,
                                               String idempotencyKey,
                                               HorizontalScalingRequest request) {
        String hash = requestHash("horizontal", request.componentName(),
                String.valueOf(request.targetReplicas()));
        OperationMetadata operation = prepare(project, databaseId, idempotencyKey,
                hash, OperationType.HORIZONTAL_SCALING);
        if (operation.getRequestHash() != null) return operationMapper.toResponse(operation);

        DatabaseMetadata database = operationDatabase(project, databaseId);
        KubeBlocksClient.ClusterComponentInfo component = kubeBlocksClient.requireComponent(
                database.getNamespaceName(), databaseId, request.componentName());
        if (component.replicas() == request.targetReplicas()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "targetReplicas must be different from the current replica count");
        }
        operation.setComponentName(component.name());
        operation.setTargetReplicas(request.targetReplicas());
        return queue(operation, hash, "Horizontal scaling request queued");
    }

    @Transactional
    public OperationResponse storageExpansion(String project, String databaseId,
                                              String idempotencyKey,
                                              StorageExpansionRequest request) {
        String volumeName = request.volumeName() == null || request.volumeName().isBlank()
                ? "data" : request.volumeName();
        String hash = requestHash("storage", request.componentName(), volumeName,
                request.newStorageSize());
        OperationMetadata operation = prepare(project, databaseId, idempotencyKey,
                hash, OperationType.STORAGE_EXPANSION);
        if (operation.getRequestHash() != null) return operationMapper.toResponse(operation);

        validateStorageLimit(request.newStorageSize());
        DatabaseMetadata database = operationDatabase(project, databaseId);
        KubeBlocksClient.ClusterComponentInfo component = kubeBlocksClient.requireComponent(
                database.getNamespaceName(), databaseId, request.componentName());
        String currentStorage = component.storage(volumeName);
        if (currentStorage == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Component " + component.name() + " has no volumeClaimTemplate named " + volumeName);
        }
        if (kubeBlocksClient.storageBytes(request.newStorageSize())
                <= kubeBlocksClient.storageBytes(currentStorage)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "newStorageSize must be greater than the current size " + currentStorage);
        }
        operation.setComponentName(component.name());
        operation.setVolumeName(volumeName);
        operation.setTargetStorageSize(request.newStorageSize());
        return queue(operation, hash, "Storage expansion request queued");
    }

    @Transactional
    public OperationResponse restart(String project, String databaseId,
                                     String idempotencyKey,
                                     RestartRequest request) {
        String componentName = request == null ? null : request.componentName();
        String hash = requestHash("restart", componentName == null ? "" : componentName);
        OperationMetadata operation = prepare(project, databaseId, idempotencyKey,
                hash, OperationType.RESTART);
        if (operation.getRequestHash() != null) return operationMapper.toResponse(operation);

        DatabaseMetadata database = operationDatabase(project, databaseId);
        if (componentName == null || componentName.isBlank()) {
            kubeBlocksClient.componentNames(database.getNamespaceName(), databaseId);
        } else {
            operation.setComponentName(kubeBlocksClient.requireComponent(
                    database.getNamespaceName(), databaseId, componentName).name());
        }
        return queue(operation, hash, componentName == null || componentName.isBlank()
                ? "Database restart request queued"
                : "Component restart request queued");
    }

    private OperationMetadata prepare(String project, String databaseId,
                                      String idempotencyKey, String requestHash,
                                      OperationType type) {
        projectService.requireActiveProject(project);
        validateIdempotencyKey(idempotencyKey);
        OperationMetadata duplicate = operationRepository
                .findByDatabaseIdAndProjectNameAndIdempotencyKey(databaseId, project, idempotencyKey)
                .orElse(null);
        if (duplicate != null) {
            if (!requestHash.equals(duplicate.getRequestHash())) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "This Idempotency-Key was already used with a different request body");
            }
            return duplicate;
        }

        DatabaseMetadata database = databaseRepository
                .findByDatabaseIdAndProjectNameForUpdate(databaseId, project)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Database " + databaseId + " was not found in project " + project));
        if (database.getStatus() != DatabaseStatus.RUNNING) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Database must be RUNNING before lifecycle operations can be submitted");
        }
        List<OperationMetadata> active = operationRepository
                .findByDatabaseIdAndProjectNameAndStatusIn(databaseId, project,
                        List.of(OperationStatus.PENDING, OperationStatus.RUNNING));
        if (!active.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Operation " + active.get(0).getOperationId()
                            + " is already running for database " + databaseId);
        }

        Instant now = Instant.now();
        String operationId = "op-" + shortId();
        return OperationMetadata.builder()
                .operationId(operationId)
                .opsRequestName(operationId)
                .databaseId(databaseId)
                .projectName(project)
                .type(type)
                .status(OperationStatus.PENDING)
                .provisioningStage(ProvisioningStage.QUEUED)
                .progress(0)
                .message("Waiting for background worker")
                .idempotencyKey(idempotencyKey)
                .createdAt(now)
                .build();
    }

    private DatabaseMetadata operationDatabase(String project, String databaseId) {
        return databaseRepository.findByDatabaseIdAndProjectName(databaseId, project)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Database " + databaseId + " was not found in project " + project));
    }

    private OperationResponse queue(OperationMetadata operation, String requestHash, String message) {
        operation.setRequestHash(requestHash);
        operation.setMessage(message);
        try {
            operationRepository.save(operation);
        } catch (DataIntegrityViolationException exception) {
            OperationMetadata duplicate = operationRepository
                    .findByDatabaseIdAndProjectNameAndIdempotencyKey(
                            operation.getDatabaseId(), operation.getProjectName(), operation.getIdempotencyKey())
                    .orElseThrow(() -> exception);
            if (!requestHash.equals(duplicate.getRequestHash())) throw exception;
            return operationMapper.toResponse(duplicate);
        }
        submitAfterCommit(operation.getOperationId());
        return operationMapper.toResponse(operation);
    }

    private void submitAfterCommit(String operationId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            submitter.submit(operationId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                submitter.submit(operationId);
            }
        });
    }

    private void validateResources(VerticalScalingRequest request) {
        int requestedCpu = cpuMillis(request.requests().cpu());
        int limitedCpu = cpuMillis(request.limits().cpu());
        int requestedMemory = memoryMi(request.requests().memory());
        int limitedMemory = memoryMi(request.limits().memory());
        if (requestedCpu <= 0 || limitedCpu <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CPU quantities must be greater than zero");
        }
        if (requestedMemory <= 0 || limitedMemory <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Memory quantities must be greater than zero");
        }
        if (requestedCpu > 64_000 || limitedCpu > 64_000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CPU quantities cannot exceed 64 cores");
        }
        if (requestedMemory > 262_144 || limitedMemory > 262_144) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Memory quantities cannot exceed 256Gi");
        }
        if (requestedCpu > limitedCpu) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "requests.cpu cannot exceed limits.cpu");
        }
        if (requestedMemory > limitedMemory) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "requests.memory cannot exceed limits.memory");
        }
    }

    private int cpuMillis(String value) {
        return value.endsWith("m")
                ? Integer.parseInt(value.substring(0, value.length() - 1))
                : (int) Math.round(Double.parseDouble(value) * 1000);
    }

    private int memoryMi(String value) {
        return value.endsWith("Gi")
                ? Integer.parseInt(value.substring(0, value.length() - 2)) * 1024
                : Integer.parseInt(value.substring(0, value.length() - 2));
    }

    private void validateStorageLimit(String quantity) {
        if (storageGi(quantity) > 2048) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "newStorageSize cannot exceed 2048Gi");
        }
    }

    private int storageGi(String quantity) {
        if (quantity.endsWith("Ti")) {
            return Integer.parseInt(quantity.substring(0, quantity.length() - 2)) * 1024;
        }
        if (quantity.endsWith("Gi")) {
            return Integer.parseInt(quantity.substring(0, quantity.length() - 2));
        }
        return Math.max(1, Integer.parseInt(quantity.substring(0, quantity.length() - 2)) / 1024);
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must be 8-128 characters using letters, numbers, '.', '_', ':' or '-'");
        }
    }

    private String requestHash(String... values) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.join("|", values).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
