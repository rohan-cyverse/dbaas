package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.client.DatabaseObservation;
import com.cyfuture.dbaas.config.DatabaseProperties;
import com.cyfuture.dbaas.dto.AccessRulesRequest;
import com.cyfuture.dbaas.dto.AccessRulesResponse;
import com.cyfuture.dbaas.dto.ConnectionResponse;
import com.cyfuture.dbaas.dto.BackupSettingsRequest;
import com.cyfuture.dbaas.dto.CreateDatabaseRequest;
import com.cyfuture.dbaas.dto.CreateDatabaseResponse;
import com.cyfuture.dbaas.dto.DatabaseResponse;
import com.cyfuture.dbaas.dto.DatabaseTopologyMemberResponse;
import com.cyfuture.dbaas.dto.DatabaseTopologyResponse;
import com.cyfuture.dbaas.dto.DeleteDatabaseResponse;
import com.cyfuture.dbaas.dto.PublicEndpointResponse;
import com.cyfuture.dbaas.dto.OperationResponse;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.entity.OperationMetadata;
import com.cyfuture.dbaas.entity.BackupPolicyMetadata;
import com.cyfuture.dbaas.entity.RestoreRequestMetadata;
import com.cyfuture.dbaas.exception.ApiException;
import com.cyfuture.dbaas.model.DatabaseEngine;
import com.cyfuture.dbaas.model.DatabaseMode;
import com.cyfuture.dbaas.model.DatabaseStatus;
import com.cyfuture.dbaas.model.OperationStatus;
import com.cyfuture.dbaas.model.OperationType;
import com.cyfuture.dbaas.model.SizePlan;
import com.cyfuture.dbaas.model.ProvisioningStage;
import com.cyfuture.dbaas.model.DesiredState;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import com.cyfuture.dbaas.repository.OperationMetadataRepository;
import com.cyfuture.dbaas.repository.BackupMetadataRepository;
import com.cyfuture.dbaas.repository.RestoreRequestMetadataRepository;
import com.cyfuture.dbaas.model.BackupStatus;
import com.cyfuture.dbaas.model.RestoreStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.net.URLEncoder;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class DatabaseService {
    private static final Pattern CIDR = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1?\\d?\\d)/(3[0-2]|[12]?\\d)$");
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9._:-]{8,128}$");
    private static final int MAX_ALLOWED_CIDRS = 10;
    private static final int MAX_DISPLAY_NAME_LENGTH = 32;
    private static final int NAME_ALLOCATION_ATTEMPTS = 12;

    private final KubeBlocksClient kubeBlocksClient;
    private final DatabaseProperties properties;
    private final DatabaseMetadataRepository databaseRepository;
    private final AsyncProvisioningService provisioningService;
    private final MetadataCreationService metadataCreationService;
    private final CredentialLifecycleService credentialLifecycleService;
    private final ProjectService projectService;
    private final SharedGatewayService sharedGatewayService;
    private final OperationMetadataRepository operationRepository;
    private final FriendlyNameGenerator friendlyNameGenerator;
    private final BackupMetadataRepository backupRepository;
    private final RestoreRequestMetadataRepository restoreRepository;
    private final BackupPolicyService backupPolicyService;
    private final BackupRetentionService backupRetentionService;

    public CreateDatabaseResponse create(String project, String idempotencyKey,
                                         CreateDatabaseRequest request) {
        return create(project, idempotencyKey, request, null);
    }

    public CreateDatabaseResponse create(String project, String idempotencyKey,
                                         CreateDatabaseRequest request, String clientIp) {
        String namespace = projectService.requireActiveProject(project)
                .getNamespaceName();
        validateIdempotencyKey(idempotencyKey);
        request = publicRequest(request, clientIp);
        validateBackupConfigurationForCreation(request.backup());
        request = withBackup(request, backupPolicyService.normalizeForCreation(request.backup()));
        String requestHash = requestHash(request);
        DatabaseMetadata existing = databaseRepository
                .findByProjectNameAndIdempotencyKey(project, idempotencyKey)
                .orElse(null);
        if (existing != null) return duplicateResponse(existing, requestHash);

        request = withAllocatedName(project, request);

        validateVersion(request);
        validateMode(request);
        validateNetwork(request.allowedCidrs(), true);

        String databaseId = "db-" + shortId();
        String operationId = "op-" + shortId();
        Instant now = Instant.now();

        DatabaseMetadata database = new DatabaseMetadata();
        database.setDatabaseId(databaseId);
        database.setOperationId(operationId);
        database.setIdempotencyKey(idempotencyKey);
        database.setRequestHash(requestHash);
        database.setProjectName(project);
        database.setNamespaceName(namespace);
        database.setDisplayName(request.name());
        database.setRemark(request.remark());
        database.setEngine(request.engine());
        database.setMode(request.mode());
        database.setDatabaseVersion(request.version());
        database.setSizePlan(request.size());
        database.setStorageGi(request.storageGi());
        database.setDeletionProtection(request.deletionProtection());
        database.setDesiredState(DesiredState.RUNNING);
        database.setStatus(DatabaseStatus.PROVISIONING);
        database.setExpectedReplicas(request.mode() == DatabaseMode.SHARDING
                ? request.shards() * request.replicas() + 5
                : request.replicas());
        database.setObservedReadyReplicas(0);
        database.setObservedServiceReady(false);
        database.setProvisioningStage(ProvisioningStage.QUEUED);
        database.setProgress(0);
        database.setReplicas(request.replicas());
        database.setShards(request.shards());
        database.setTimezone(request.timezone());
        database.setAllowedCidrs(safeCidrs(request.allowedCidrs()).stream().sorted().toList().toString());
        database.setTags(request.tags() == null ? "{}" : request.tags().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .toList().toString());
        database.setMessage("Provisioning request queued");
        database.setCreatedAt(now);
        database.setUpdatedAt(now);
        BackupPolicyMetadata backupPolicy = backupPolicyService.initialPolicy(database, request.backup(), operationId);
        if (backupPolicy == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "BACKUP_POLICY_INITIALIZATION_FAILED", true,
                    "Backup configuration could not be initialized. Retry the database creation request.");
        }
        try {
            metadataCreationService.save(database, operationFor(operationId, databaseId, project, now), backupPolicy);
        } catch (DataIntegrityViolationException exception) {
            DatabaseMetadata duplicate = databaseRepository
                    .findByProjectNameAndIdempotencyKey(project, idempotencyKey)
                    .orElseThrow(() -> exception);
            return duplicateResponse(duplicate, requestHash);
        }

        provisioningService.provision(operationId, databaseId, project,
                namespace, request);
        return createResponse(database, "Provisioning request accepted.");
    }

    private OperationMetadata operationFor(String operationId, String databaseId,
                                           String project, Instant now) {
        return OperationMetadata.builder()
                .operationId(operationId)
                .databaseId(databaseId)
                .projectName(project)
                .type(OperationType.CREATE)
                .status(OperationStatus.PENDING)
                .provisioningStage(ProvisioningStage.QUEUED)
                .progress(0)
                .message("Waiting for background worker")
                .createdAt(now)
                .build();
    }

    private CreateDatabaseResponse createResponse(DatabaseMetadata database, String message) {
        return new CreateDatabaseResponse(database.getDatabaseId(), database.getDisplayName(),
                database.getOperationId(),
                database.getStatus(), database.getProvisioningStage(),
                database.getProgress(), message);
    }

    private CreateDatabaseResponse duplicateResponse(DatabaseMetadata database, String requestHash) {
        if (!requestHash.equals(database.getRequestHash())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This Idempotency-Key was already used with a different request body");
        }
        return createResponse(database,
                "Returning the existing provisioning request.");
    }

    private String requestHash(CreateDatabaseRequest request) {
        String tags = request.tags() == null ? "" : request.tags().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .toList().toString();
        String value = request.name() + "|" + request.remark() + "|" + request.engine()
                + "|" + request.mode() + "|" + request.version() + "|" + request.size()
                + "|" + request.storageGi() + "|" + request.replicas() + "|" + request.shards()
                + "|" + request.timezone() + "|" + request.deletionProtection() + "|" + tags
                + "|" + backupHash(request);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    public DatabaseResponse get(String project, String databaseId) {
        DatabaseMetadata metadata = requireDatabase(project, databaseId);
        if (metadata.getStatus() == DatabaseStatus.FAILED
                || metadata.getStatus() == DatabaseStatus.MISSING
                || metadata.getStatus() == DatabaseStatus.DELETED) {
            return fromMetadata(metadata);
        }
        try {
            DatabaseObservation live = kubeBlocksClient.get(metadata.getNamespaceName(), metadata.physicalClusterName());
            syncLiveStatus(metadata, live);
            return withPublicAccess(metadata, live, true);
        } catch (ApiException exception) {
            if (metadata.getStatus() == DatabaseStatus.PROVISIONING) return fromMetadata(metadata);
            throw exception;
        }
    }

    public List<DatabaseResponse> list(String project) {
        validateProject(project);

        return databaseRepository
                .findByProjectNameOrderByCreatedAtDesc(project)
                .stream()
                .map(this::getDatabaseForList)
                .toList();
    }

    @Transactional
    public ConnectionResponse connection(String project,
                                         String databaseId, String clientIp) {
        DatabaseMetadata database = databaseRepository
                .findByDatabaseIdAndProjectNameForUpdate(databaseId, project)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Database " + databaseId + " was not found"));
        if (database.getStatus() == DatabaseStatus.FAILED) {
            throw new ApiException(HttpStatus.CONFLICT, "Failed database has no credentials");
        }
        if (database.getStatus() != DatabaseStatus.RUNNING
                || stage(database) != ProvisioningStage.READY) {
            throw new ApiException(HttpStatus.CONFLICT, "DATABASE_NOT_READY", true,
                    "Database connection is not ready; current stage is " + stage(database));
        }
        DatabaseObservation live = kubeBlocksClient.get(database.getNamespaceName(), database.physicalClusterName());
        if (live.status() != DatabaseStatus.RUNNING || !live.serviceReady()) {
            throw new ApiException(HttpStatus.CONFLICT, "DATABASE_NOT_READY", true,
                    "Database is not ready for connections");
        }
        // A restored target must continue to use the logical database from
        // its recovery point. Re-establish that specific credential first if
        // its managed Secret was removed; never fall back to creating a
        // target-ID-named database.
        RestoreRequestMetadata restore = restoreRepository
                .findFirstByRestoredDatabaseIdAndStatusInOrderByCreatedAtDesc(databaseId,
                        List.of(RestoreStatus.COMPLETED, RestoreStatus.READY))
                .orElse(null);
        if (restore != null) {
            if (!credentialLifecycleService.readyForRestoredDatabase(database,
                    CredentialLifecycleService.managedDatabaseName(restore.getSourceDatabaseId()),
                    CredentialLifecycleService.managedUsername(restore.getSourceDatabaseId()))) {
                throw new ApiException(HttpStatus.CONFLICT, "RESTORED_CREDENTIALS_NOT_READY", true,
                        "Restored database credentials are being prepared; retry shortly.");
            }
        }
        ManagedCredential credential = credentialLifecycleService.credentials(database);
        PublicEndpointResponse publicEndpoint = publicEndpoint(database);
        if (restore != null && restore.getAccessMode() == com.cyfuture.dbaas.model.RestoreAccessMode.PRIVATE) {
            String host = database.physicalClusterName() + "." + database.getNamespaceName() + ".svc.cluster.local";
            return new ConnectionResponse(credential.username(), credential.password(),
                    connectionUri(database.getEngine(), database.getMode(), false,
                            credential.username(), credential.password(), host,
                            defaultPort(database.getEngine()), credential.database()), null);
        }
        if (!publicEndpoint.ready() || publicEndpoint.host() == null
                || publicEndpoint.host().isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PUBLIC_ENDPOINT_NOT_READY", true,
                    "The public endpoint is not ready");
        }
        return new ConnectionResponse(credential.username(), credential.password(),
                connectionUri(database.getEngine(), database.getMode(), true,
                        credential.username(), credential.password(), publicEndpoint.host(),
                        publicEndpoint.port(), credential.database()), publicEndpoint);
    }

    public OperationResponse rotateCredentials(String project,
                                               String databaseId) {
        return credentialLifecycleService.rotate(
                requireDatabase(project, databaseId));
    }

    public AccessRulesResponse accessRules(String project, String databaseId) {
        DatabaseMetadata database = requireDatabase(project, databaseId);
        return new AccessRulesResponse(database.getDatabaseId(),
                metadataCidrs(database), publicEndpoint(database));
    }

    public AccessRulesResponse updateAccessRules(String project, String databaseId,
                                                 AccessRulesRequest request, String clientIp) {
        DatabaseMetadata database = requireDatabase(project, databaseId);
        List<String> cidrs = applyAccessRuleChanges(database, request, clientIp);
        validateNetwork(cidrs, database.getPublicPort() != null);
        database.setAllowedCidrs(cidrs.toString());
        database.setUpdatedAt(Instant.now());
        databaseRepository.save(database);
        sharedGatewayService.reconcileNow();
        return new AccessRulesResponse(database.getDatabaseId(), cidrs, publicEndpoint(database));
    }

    public DeleteDatabaseResponse delete(String project, String databaseId) {
        DatabaseMetadata database = requireDatabase(project, databaseId);
        if (database.getStatus() == DatabaseStatus.DELETED) {
            return new DeleteDatabaseResponse(DatabaseStatus.DELETED,
                    ClientMessages.database(DatabaseStatus.DELETED, stage(database)));
        }
        if (database.getStatus() == DatabaseStatus.DELETING) {
            return deletionResponse(database);
        }
        if (database.isDeletionProtection()) {
            throw new ApiException(HttpStatus.CONFLICT, "DELETION_PROTECTION_ENABLED", false,
                    "Deletion protection is enabled for " + databaseId
                            + ". Disable it before deleting.");
        }
        if (restoreRepository.existsByProjectNameAndSourceDatabaseIdAndStatusIn(project, databaseId,
                List.of(RestoreStatus.PENDING, RestoreStatus.SAFETY_BACKUP, RestoreStatus.RESTORING,
                        RestoreStatus.VALIDATING, RestoreStatus.CUTTING_OVER, RestoreStatus.ROLLING_BACK,
                        RestoreStatus.RUNNING))
                || restoreRepository.existsByRestoredDatabaseIdAndStatusIn(databaseId,
                List.of(RestoreStatus.PENDING, RestoreStatus.SAFETY_BACKUP, RestoreStatus.RESTORING,
                        RestoreStatus.VALIDATING, RestoreStatus.CUTTING_OVER, RestoreStatus.ROLLING_BACK,
                        RestoreStatus.RUNNING))) {
            throw new ApiException(HttpStatus.CONFLICT, "RESTORE_IN_PROGRESS", false,
                    "A restore is using this database. Wait for it to finish before deleting the database.");
        }
        operationRepository.findByDatabaseIdAndProjectNameAndStatusIn(databaseId, project,
                        List.of(OperationStatus.PENDING, OperationStatus.RUNNING))
                .stream()
                .filter(operation -> operation.getType() != OperationType.DELETE)
                .findFirst()
                .ifPresent(operation -> {
                    throw new ApiException(HttpStatus.CONFLICT, "DATABASE_OPERATION_IN_PROGRESS", false,
                            "Operation " + operation.getOperationId() + " is still running. "
                                    + "Wait for it to finish before deleting the database.");
                });

        OperationMetadata operation = deleteOperation(database);
        database.setDesiredState(DesiredState.DELETED);
        database.setStatus(DatabaseStatus.DELETING);
        database.setDeleteRequestedAt(Instant.now());
        database.setMessage("Database deletion requested; removing public route");
        database.setUpdatedAt(Instant.now());
        databaseRepository.save(database);

        backupRetentionService.prepareDatabaseBackupDeletion(project, databaseId);
        if (!backupRetentionService.readyForClusterDeletion(project, databaseId)
                || kubeBlocksClient.hasActiveBackup(database.getNamespaceName(), database.physicalClusterName())) {
            database.setMessage("Database deletion is removing backups before deleting the database");
            database.setUpdatedAt(Instant.now());
            databaseRepository.save(database);
            return deletionResponse(database);
        }

        try {
            sharedGatewayService.removeRoute(database);
        } catch (Exception exception) {
            log.warn("Database {} public-route removal will retry", databaseId, exception);
            database.setMessage("Database deletion is waiting for public route removal");
            database.setUpdatedAt(Instant.now());
            databaseRepository.save(database);
            return deletionResponse(database);
        }

        try {
            CredentialLifecycleService.CredentialCleanupObservation credentialCleanup =
                    credentialLifecycleService.cleanupDatabaseResources(database);
            if (!credentialCleanup.complete()) {
                database.setMessage("Database deletion is waiting for credential helper cleanup: "
                        + credentialCleanup.message());
                database.setUpdatedAt(Instant.now());
                databaseRepository.save(database);
                return deletionResponse(database);
            }
            kubeBlocksClient.requestDelete(database.getNamespaceName(), database.physicalClusterName());
            KubeBlocksClient.ClusterObservation observation = kubeBlocksClient.observeCluster(
                    database.getNamespaceName(), database.physicalClusterName());
            if (!observation.exists()) {
                CredentialLifecycleService.CredentialCleanupObservation remaining =
                        credentialLifecycleService.cleanupDatabaseResources(database);
                if (remaining.complete()) {
                    sharedGatewayService.releasePort(database);
                    database.setStatus(DatabaseStatus.DELETED);
                    database.setDeletedAt(Instant.now());
                    database.setMessage("Database Cluster and credential helper resources are absent; metadata is preserved");
                    database.setUpdatedAt(Instant.now());
                    databaseRepository.save(database);
                    finishDeleteOperation(operation, OperationStatus.SUCCEEDED, database.getMessage());
                    return deletionResponse(database);
                }
                database.setMessage("Database Cluster is absent; waiting for credential helper cleanup: "
                        + remaining.message());
                database.setUpdatedAt(Instant.now());
                databaseRepository.save(database);
                return deletionResponse(database);
            }
            database.setMessage("KubeBlocks deletion is running");
            database.setUpdatedAt(Instant.now());
            databaseRepository.save(database);
        } catch (Exception exception) {
            log.warn("Database {} deletion confirmation will retry", databaseId, exception);
            database.setMessage("Database deletion is waiting for Kubernetes confirmation");
            database.setUpdatedAt(Instant.now());
            databaseRepository.save(database);
        }
        return deletionResponse(database);
    }

    public DatabaseResponse setDeletionProtection(String project,
                                                  String databaseId, boolean enabled) {
        DatabaseMetadata metadata = requireDatabase(project, databaseId);
        DatabaseObservation response = kubeBlocksClient.setDeletionProtection(
                metadata.getNamespaceName(), databaseId, enabled);
        metadata.setDeletionProtection(enabled);
        metadata.setUpdatedAt(Instant.now());
        databaseRepository.save(metadata);
        return withPublicAccess(metadata, response, true);
    }

    public void validateProject(String project) {
        projectService.requireActiveProject(project);
    }

    public Map<?, ?> options() {
        return Map.of(
                "engines", properties.supportedVersions(),
                "modes", Map.of(
                        DatabaseEngine.POSTGRESQL, List.of(DatabaseMode.STANDALONE, DatabaseMode.REPLICATION),
                        DatabaseEngine.MYSQL, List.of(DatabaseMode.STANDALONE, DatabaseMode.REPLICATION),
                        DatabaseEngine.MONGODB, List.of(DatabaseMode.STANDALONE, DatabaseMode.REPLICA_SET, DatabaseMode.SHARDING)),
                "sizes", SizePlan.values(),
                "replicas", List.of(1, 2, 3),
                "storageOptionsGi", List.of(10, 20, 50, 100));
    }

    private DatabaseMetadata requireDatabase(String project, String databaseId) {
        validateProject(project);
        return databaseRepository.findByDatabaseIdAndProjectName(databaseId, project)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Database " + databaseId + " was not found in project " + project));
    }

    private DatabaseResponse fromMetadata(DatabaseMetadata database) {
        DatabaseTopologyResponse topology = metadataTopology(database);
        return new DatabaseResponse(database.getDatabaseId(), database.getDisplayName(), database.getEngine(),
                database.getDatabaseVersion(), database.getStatus(), database.getMode(), database.getSizePlan(),
                database.getStorageGi(), topology.instanceCount(), topology.primaryCount(), topology.replicaCount(),
                topology.shardCount(), topology.mongosCount(), topology.configServerCount(),
                database.isDeletionProtection(), stage(database), database.getProgress(),
                publicEndpoint(database),
                topology,
                ClientMessages.database(database.getStatus(), stage(database)));
    }

    private DatabaseResponse withPublicAccess(DatabaseMetadata metadata, DatabaseObservation live,
                                              boolean includeMembers) {
        PublicEndpointResponse publicEndpoint = publicEndpoint(metadata);
        DatabaseTopologyResponse topology = observedTopology(live, includeMembers);
        return new DatabaseResponse(metadata.getDatabaseId(), metadata.getDisplayName(),
                metadata.getEngine(), metadata.getDatabaseVersion(), metadata.getStatus(),
                metadata.getMode(), metadata.getSizePlan(), metadata.getStorageGi(),
                topology.instanceCount(), topology.primaryCount(), topology.replicaCount(),
                topology.shardCount(), topology.mongosCount(), topology.configServerCount(),
                live.deletionProtection(), stage(metadata), metadata.getProgress(), publicEndpoint,
                topology,
                ClientMessages.database(metadata.getStatus(), stage(metadata)));
    }

    private DatabaseTopologyResponse observedTopology(DatabaseObservation live, boolean includeMembers) {
        return new DatabaseTopologyResponse(
                live.instanceCount(),
                live.primaryCount(),
                live.replicaCount(),
                live.shardCount(),
                live.mongosCount(),
                live.configServerCount(),
                includeMembers ? live.members().stream()
                        .map(member -> new DatabaseTopologyMemberResponse(
                                member.name(), member.role(), member.component(), member.ready()))
                        .toList() : List.of());
    }

    private DatabaseTopologyResponse metadataTopology(DatabaseMetadata database) {
        int instanceCount = expectedInstanceCount(database);
        int primaryCount = database.getStatus() == DatabaseStatus.DELETED ? 0 : primaryCount(database);
        int replicaCount = Math.max(0, instanceCount - primaryCount
                - mongosCount(database) - configServerCount(database));
        return new DatabaseTopologyResponse(instanceCount, primaryCount, replicaCount,
                database.getMode() == DatabaseMode.SHARDING ? database.getShards() : 0,
                mongosCount(database), configServerCount(database), List.of());
    }

    private int expectedInstanceCount(DatabaseMetadata database) {
        if (database.getStatus() == DatabaseStatus.DELETED) return 0;
        if (database.getMode() == DatabaseMode.SHARDING) {
            return database.getShards() * database.getReplicas() + mongosCount(database) + configServerCount(database);
        }
        return Math.max(1, database.getReplicas());
    }

    private int primaryCount(DatabaseMetadata database) {
        if (database.getMode() == DatabaseMode.SHARDING) return Math.max(1, database.getShards());
        return 1;
    }

    private int mongosCount(DatabaseMetadata database) {
        return database.getMode() == DatabaseMode.SHARDING ? 2 : 0;
    }

    private int configServerCount(DatabaseMetadata database) {
        return database.getMode() == DatabaseMode.SHARDING ? 3 : 0;
    }

    private PublicEndpointResponse publicEndpoint(DatabaseMetadata metadata) {
        return sharedGatewayService.endpoint(metadata);
    }

    private List<String> metadataCidrs(DatabaseMetadata metadata) {
        String stored = metadata.getAllowedCidrs();
        if (stored == null || stored.isBlank() || "[]".equals(stored.trim())) return List.of();
        String content = stored.trim();
        if (content.startsWith("[") && content.endsWith("]")) {
            content = content.substring(1, content.length() - 1);
        }
        if (content.isBlank()) return List.of();
        return java.util.Arrays.stream(content.split(","))
                .map(String::trim).filter(value -> !value.isBlank()).toList();
    }

    private void syncLiveStatus(DatabaseMetadata metadata, DatabaseObservation live) {
        metadata.setExpectedReplicas(live.instanceCount());
        metadata.setObservedReadyReplicas(live.readyReplicas());
        metadata.setObservedServiceReady(live.serviceReady());
        metadata.setLastObservedAt(Instant.now());
        if (live.status() == DatabaseStatus.FAILED) {
            if (hasActiveLifecycleOperation(metadata)) return;
            metadata.setMessage("Database health requires attention");
        }
        metadata.setDeletionProtection(live.deletionProtection());
        metadata.setUpdatedAt(Instant.now());
        databaseRepository.save(metadata);
    }

    private boolean hasActiveLifecycleOperation(DatabaseMetadata metadata) {
        return operationRepository
                .findByDatabaseIdAndProjectNameAndStatusIn(metadata.getDatabaseId(),
                        metadata.getProjectName(),
                        List.of(OperationStatus.PENDING, OperationStatus.RUNNING))
                .stream()
                .anyMatch(operation -> operation.getType() != OperationType.CREATE);
    }

    private ProvisioningStage stage(DatabaseMetadata database) {
        if (database.getProvisioningStage() != null) return database.getProvisioningStage();
        if (database.getStatus() == DatabaseStatus.RUNNING) return ProvisioningStage.READY;
        if (database.getStatus() == DatabaseStatus.FAILED) return ProvisioningStage.FAILED;
        return ProvisioningStage.QUEUED;
    }

    private String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private OperationMetadata deleteOperation(DatabaseMetadata database) {
        OperationMetadata existing = operationRepository
                .findByDatabaseIdAndProjectNameAndStatusIn(database.getDatabaseId(),
                        database.getProjectName(),
                        List.of(OperationStatus.PENDING, OperationStatus.RUNNING))
                .stream()
                .filter(operation -> operation.getType() == OperationType.DELETE)
                .findFirst()
                .orElse(null);
        if (existing != null) return existing;
        OperationMetadata operation = OperationMetadata.builder()
                .operationId("op-" + shortId())
                .databaseId(database.getDatabaseId())
                .projectName(database.getProjectName())
                .type(OperationType.DELETE)
                .status(OperationStatus.RUNNING)
                .provisioningStage(ProvisioningStage.WAITING_FOR_REPLICAS)
                .progress(20)
                .message("Database deletion requested")
                .createdAt(Instant.now())
                .startedAt(Instant.now())
                .build();
        return operationRepository.save(operation);
    }

    private void finishDeleteOperation(OperationMetadata operation,
                                       OperationStatus status,
                                       String message) {
        operation.setStatus(status);
        operation.setProvisioningStage(status == OperationStatus.SUCCEEDED
                ? ProvisioningStage.READY : ProvisioningStage.FAILED);
        operation.setProgress(100);
        operation.setMessage(message);
        operation.setCompletedAt(Instant.now());
        operationRepository.save(operation);
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must be 8-128 characters using letters, numbers, '.', '_', ':' or '-'");
        }
    }

    private void validateVersion(CreateDatabaseRequest request) {
        List<String> versions = properties.engine(request.engine()).getVersions();
        if (!versions.contains(request.version())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Version " + request.version() + " is not supported for " + request.engine()
                            + ". Supported versions: " + versions);
        }
    }

    private void validateNetwork(List<String> allowedCidrs) {
        validateNetwork(allowedCidrs, true);
    }

    private void validateNetwork(List<String> allowedCidrs, boolean requireAtLeastOne) {
        List<String> cidrs = safeCidrs(allowedCidrs);
        if (requireAtLeastOne && cidrs.isEmpty())
            throw new ApiException(HttpStatus.BAD_REQUEST, "ACCESS_RULE_REQUIRED", false,
                    "At least one access rule is required for a public database.");
        if (cidrs.size() > MAX_ALLOWED_CIDRS)
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "A database can have at most " + MAX_ALLOWED_CIDRS + " access rules");
        if (cidrs.stream().anyMatch(cidr -> cidr == null || !CIDR.matcher(cidr).matches()))
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Every allowedCidrs value must be a valid IPv4 CIDR such as 49.50.73.146/32");
    }

    /** New database requests must make backup behavior an explicit customer choice. */
    private void validateBackupConfigurationForCreation(BackupSettingsRequest backup) {
        if (backup == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BACKUP_SETTINGS_REQUIRED", false,
                    "backup configuration is required when creating a database.");
        }
        if (backup.scheduled() == null || backup.retentionDays() == null
                || backup.timezone() == null || backup.timezone().isBlank()
                || backup.pitrEnabled() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BACKUP_SETTINGS_INCOMPLETE", false,
                    "backup must explicitly set scheduled, retentionDays, timezone, and pitrEnabled.");
        }
        if (Boolean.TRUE.equals(backup.scheduled())
                && (backup.schedule() == null || backup.schedule().isBlank())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BACKUP_CRON_REQUIRED", false,
                    "schedule is required when scheduled backups are enabled.");
        }
    }

    private List<String> safeCidrs(List<String> cidrs) {
        return cidrs == null ? List.of() : cidrs;
    }

    private List<String> applyAccessRuleChanges(DatabaseMetadata database,
                                                AccessRulesRequest request,
                                                String clientIp) {
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>(metadataCidrs(database));
        normalizeAccessRules(request.cidrsToAdd(), List.of(), request.includeCurrentClientIp(), clientIp)
                .forEach(merged::add);
        normalizeAccessRules(request.cidrsToRemove(), List.of(), false, null)
                .forEach(merged::remove);
        return merged.stream().sorted().toList();
    }

    private List<String> normalizeAccessRules(List<String> allowedCidrs,
                                              List<String> addCidrs,
                                              boolean includeCurrentClientIp,
                                              String clientIp) {
        if (safeCidrs(allowedCidrs).stream().anyMatch(java.util.Objects::isNull)
                || safeCidrs(addCidrs).stream().anyMatch(java.util.Objects::isNull)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ACCESS_RULE", false,
                    "allowedCidrs cannot contain null values. Use an IPv4 CIDR such as 49.50.73.146/32.");
        }
        java.util.LinkedHashSet<String> normalized = new java.util.LinkedHashSet<>();
        safeCidrs(allowedCidrs).stream()
                .filter(cidr -> cidr != null && !cidr.isBlank())
                .map(String::trim)
                .map(cidr -> cidr.matches("^(\\d{1,3}\\.){3}\\d{1,3}$") ? cidr + "/32" : cidr)
                .forEach(normalized::add);
        safeCidrs(addCidrs).stream()
                .filter(cidr -> cidr != null && !cidr.isBlank())
                .map(String::trim)
                .map(cidr -> cidr.matches("^(\\d{1,3}\\.){3}\\d{1,3}$") ? cidr + "/32" : cidr)
                .forEach(normalized::add);
        if (includeCurrentClientIp) {
            if (clientIp == null || clientIp.isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "Could not detect the caller public IP for database access");
            }
            normalized.add(clientIp.trim() + "/32");
        }
        return normalized.stream().sorted().toList();
    }

    private CreateDatabaseRequest publicRequest(CreateDatabaseRequest request,
                                                String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Could not detect the caller public IP for database access");
        }
        List<String> cidrs = List.of(clientIp + "/32");
        return new CreateDatabaseRequest(request.name(), request.remark(), request.engine(),
                request.mode(), request.version(), request.size(), request.storageGi(),
                request.replicas(), request.shards(), request.timezone(), cidrs,
                request.deletionProtection(), request.tags(), request.backup());
    }

    private CreateDatabaseRequest withAllocatedName(String project, CreateDatabaseRequest request) {
        String requestedName = request.name() == null ? null : request.name().trim();
        String displayName = requestedName == null || requestedName.isBlank()
                ? allocateGeneratedName(project, request.engine())
                : allocateRequestedName(project, requestedName);
        return new CreateDatabaseRequest(displayName, request.remark(), request.engine(),
                request.mode(), request.version(), request.size(), request.storageGi(),
                request.replicas(), request.shards(), request.timezone(), request.allowedCidrs(),
                request.deletionProtection(), request.tags(), request.backup());
    }

    private CreateDatabaseRequest withBackup(CreateDatabaseRequest request,
                                             com.cyfuture.dbaas.dto.BackupSettingsRequest backup) {
        return new CreateDatabaseRequest(request.name(), request.remark(), request.engine(), request.mode(),
                request.version(), request.size(), request.storageGi(), request.replicas(), request.shards(),
                request.timezone(), request.allowedCidrs(), request.deletionProtection(), request.tags(), backup);
    }

    private String backupHash(CreateDatabaseRequest request) {
        if (request.backup() == null) return "";
        return String.valueOf(request.backup().scheduled()) + "|" + request.backup().retentionDays() + "|"
                + request.backup().schedule() + "|" + request.backup().timezone() + "|"
                + request.backup().pitrEnabled();
    }

    private String allocateGeneratedName(String project, DatabaseEngine engine) {
        for (int attempt = 0; attempt < NAME_ALLOCATION_ATTEMPTS; attempt++) {
            String candidate = friendlyNameGenerator.nextDatabaseName(engine);
            if (!databaseRepository.existsByProjectNameAndDisplayName(project, candidate)) {
                return candidate;
            }
        }
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_NAME_ALLOCATION_FAILED", true,
                "Unable to allocate a unique database name; retry the request");
    }

    private String allocateRequestedName(String project, String requestedName) {
        if (!databaseRepository.existsByProjectNameAndDisplayName(project, requestedName)) {
            return requestedName;
        }
        for (int attempt = 0; attempt < NAME_ALLOCATION_ATTEMPTS; attempt++) {
            String candidate = appendSuffix(requestedName, friendlyNameGenerator.nextShortSuffix());
            if (!databaseRepository.existsByProjectNameAndDisplayName(project, candidate)) {
                return candidate;
            }
        }
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_NAME_ALLOCATION_FAILED", true,
                "Unable to allocate a unique database name; retry the request");
    }

    private String appendSuffix(String name, String suffix) {
        int baseLength = MAX_DISPLAY_NAME_LENGTH - suffix.length() - 1;
        return name.substring(0, Math.min(name.length(), baseLength)) + "-" + suffix;
    }

    private String connectionUri(DatabaseEngine engine, DatabaseMode mode, boolean publicRoute,
                                 String username, String password,
                                 String host, int port, String database) {
        if (host == null || host.isBlank()) return null;
        String user = urlEncode(username);
        String secret = urlEncode(password);
        return switch (engine) {
            case POSTGRESQL -> "postgresql://" + user + ":" + secret + "@" + host + ":"
                    + port + "/" + database + "?sslmode=prefer";
            case MYSQL -> "mysql://" + user + ":" + secret + "@" + host + ":"
                    + port + "/" + database;
            case MONGODB -> "mongodb://" + user + ":" + secret + "@" + host + ":"
                    + port + "/" + database + "?authSource=" + database
                    + (publicRoute && mode != DatabaseMode.SHARDING
                    ? "&directConnection=true" : "");
        };
    }

    private int defaultPort(DatabaseEngine engine) {
        return switch (engine) {
            case POSTGRESQL -> 5432;
            case MYSQL -> 3306;
            case MONGODB -> 27017;
        };
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private void validateMode(CreateDatabaseRequest request) {
        boolean valid = switch (request.engine()) {
            case POSTGRESQL, MYSQL -> request.mode() == DatabaseMode.STANDALONE
                    || request.mode() == DatabaseMode.REPLICATION;
            case MONGODB -> request.mode() == DatabaseMode.STANDALONE
                    || request.mode() == DatabaseMode.REPLICA_SET
                    || request.mode() == DatabaseMode.SHARDING;
        };
        if (!valid) throw new ApiException(HttpStatus.BAD_REQUEST,
                request.mode() + " is not valid for " + request.engine());
        if (request.mode() == DatabaseMode.STANDALONE && request.replicas() != 1)
            throw new ApiException(HttpStatus.BAD_REQUEST, "STANDALONE requires replicas=1");
        if (request.mode() == DatabaseMode.REPLICATION && request.replicas() < 2)
            throw new ApiException(HttpStatus.BAD_REQUEST, "REPLICATION requires at least 2 replicas");
        if (request.mode() == DatabaseMode.REPLICA_SET && request.replicas() < 2)
            throw new ApiException(HttpStatus.BAD_REQUEST, "REPLICA_SET requires at least 2 replicas");
        if (request.mode() == DatabaseMode.SHARDING && request.shards() < 2)
            throw new ApiException(HttpStatus.BAD_REQUEST, "MongoDB SHARDING requires at least 2 shards");
    }

    private DatabaseResponse getDatabaseForList(
            DatabaseMetadata database
    ) {
        try {
            DatabaseObservation live = kubeBlocksClient.get(database.getNamespaceName(), database.physicalClusterName());
            syncLiveStatus(database, live);
            return withPublicAccess(database, live, false);
        } catch (ApiException exception) {
            return metadataOnlyResponse(database);
        }
    }

    private DatabaseResponse metadataOnlyResponse(
            DatabaseMetadata database
    ) {
        return fromMetadata(database);
    }

    private DeleteDatabaseResponse deletionResponse(DatabaseMetadata database) {
        return new DeleteDatabaseResponse(database.getStatus(),
                ClientMessages.database(database.getStatus(), stage(database)));
    }

}
