package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.config.DatabaseProperties;
import com.cyfuture.dbaas.dto.ConnectionResponse;
import com.cyfuture.dbaas.dto.CreateDatabaseRequest;
import com.cyfuture.dbaas.dto.CreateDatabaseResponse;
import com.cyfuture.dbaas.dto.DatabaseResponse;
import com.cyfuture.dbaas.dto.DeleteDatabaseResponse;
import com.cyfuture.dbaas.dto.PrivateEndpointResponse;
import com.cyfuture.dbaas.dto.PublicEndpointResponse;
import com.cyfuture.dbaas.dto.OperationResponse;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.entity.OperationMetadata;
import com.cyfuture.dbaas.exception.ApiException;
import com.cyfuture.dbaas.model.DatabaseEngine;
import com.cyfuture.dbaas.model.DatabaseMode;
import com.cyfuture.dbaas.model.DatabaseStatus;
import com.cyfuture.dbaas.model.OperationStatus;
import com.cyfuture.dbaas.model.OperationType;
import com.cyfuture.dbaas.model.SizePlan;
import com.cyfuture.dbaas.model.ProvisioningStage;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

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
public class DatabaseService {
    private static final Pattern CIDR = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1?\\d?\\d)/(3[0-2]|[12]?\\d)$");
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9._:-]{8,128}$");

    private final KubeBlocksClient kubeBlocksClient;
    private final DatabaseProperties properties;
    private final DatabaseMetadataRepository databaseRepository;
    private final AsyncProvisioningService provisioningService;
    private final MetadataCreationService metadataCreationService;
    private final CredentialLifecycleService credentialLifecycleService;
    private final ProjectService projectService;
    private final SharedGatewayService sharedGatewayService;

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
        String requestHash = requestHash(request);
        DatabaseMetadata existing = databaseRepository
                .findByProjectNameAndIdempotencyKey(project, idempotencyKey)
                .orElse(null);
        if (existing != null) return duplicateResponse(existing, requestHash);

        validateVersion(request);
        validateMode(request);
        validateNetwork(request.allowedCidrs());

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
        database.setStatus(DatabaseStatus.PROVISIONING);
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
        try {
            metadataCreationService.save(database,
                    operationFor(operationId, databaseId, project, now));
        } catch (DataIntegrityViolationException exception) {
            DatabaseMetadata duplicate = databaseRepository
                    .findByProjectNameAndIdempotencyKey(project, idempotencyKey)
                    .orElseThrow(() -> exception);
            return duplicateResponse(duplicate, requestHash);
        }

        provisioningService.provision(operationId, databaseId, project,
                namespace, request);
        return createResponse(database, "Database provisioning request queued");
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
        return new CreateDatabaseResponse(database.getDatabaseId(), database.getOperationId(),
                database.getProjectName(),
                database.getNamespaceName(), database.getDisplayName(),
                database.getEngine(), database.getStatus(), database.getProvisioningStage(),
                database.getProgress(), statusUrl(database), operationUrl(database), message);
    }

    private String statusUrl(DatabaseMetadata database) {
        return databaseBaseUrl(database) + "/" + database.getDatabaseId();
    }

    private String operationUrl(DatabaseMetadata database) {
        return statusUrl(database) + "/operations/" + database.getOperationId();
    }

    private String databaseBaseUrl(DatabaseMetadata database) {
        return "/api/v1/projects/" + database.getProjectName() + "/databases";
    }

    private CreateDatabaseResponse duplicateResponse(DatabaseMetadata database, String requestHash) {
        if (!requestHash.equals(database.getRequestHash())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This Idempotency-Key was already used with a different request body");
        }
        return createResponse(database,
                "Duplicate request detected; returning the existing database operation");
    }

    private String requestHash(CreateDatabaseRequest request) {
        String tags = request.tags() == null ? "" : request.tags().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .toList().toString();
        String value = request.name() + "|" + request.remark() + "|" + request.engine()
                + "|" + request.mode() + "|" + request.version() + "|" + request.size()
                + "|" + request.storageGi() + "|" + request.replicas() + "|" + request.shards()
                + "|" + request.timezone() + "|" + request.deletionProtection() + "|" + tags;
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
        if (metadata.getStatus() == DatabaseStatus.FAILED) return fromMetadata(metadata);
        try {
            DatabaseResponse live = kubeBlocksClient.get(metadata.getNamespaceName(), databaseId);
            syncLiveStatus(metadata, live);
            return withPublicAccess(metadata, live);
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

    public ConnectionResponse connection(String project,
                                         String databaseId, String clientIp) {
        DatabaseMetadata database = requireDatabase(project, databaseId);
        if (database.getStatus() == DatabaseStatus.FAILED) {
            throw new ApiException(HttpStatus.CONFLICT, "Failed database has no credentials");
        }
        if (database.getStatus() != DatabaseStatus.RUNNING
                || stage(database) != ProvisioningStage.READY) {
            throw new ApiException(HttpStatus.CONFLICT, "DATABASE_NOT_READY", true,
                    "Database connection is not ready; current stage is " + stage(database));
        }
        authorizeCaller(database, clientIp);
        DatabaseResponse live = kubeBlocksClient.get(database.getNamespaceName(), databaseId);
        if (live.status() != DatabaseStatus.RUNNING || live.privateEndpoint() == null
                || !live.privateEndpoint().ready()) {
            throw new ApiException(HttpStatus.CONFLICT, "DATABASE_NOT_READY", true,
                    "Database is not ready for connections");
        }
        ManagedCredential credential = credentialLifecycleService.credentials(database);
        PublicEndpointResponse publicEndpoint = publicEndpoint(database);
        return new ConnectionResponse(databaseId, database.getEngine(),
                credential.database(), credential.username(), credential.password(),
                connectionUri(database.getEngine(), database.getMode(), false,
                        credential.username(), credential.password(),
                        live.privateEndpoint().host(), live.privateEndpoint().port(),
                        credential.database()),
                publicEndpoint.ready() ? connectionUri(database.getEngine(), database.getMode(),
                        true, credential.username(), credential.password(), publicEndpoint.host(),
                        publicEndpoint.port(), credential.database()) : null,
                live.privateEndpoint(), publicEndpoint);
    }

    public OperationResponse rotateCredentials(String project,
                                               String databaseId) {
        return credentialLifecycleService.rotate(
                requireDatabase(project, databaseId));
    }

    public DeleteDatabaseResponse delete(String project, String databaseId) {
        DatabaseMetadata database = requireDatabase(project, databaseId);
        if (database.isDeletionProtection()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Deletion protection is enabled for " + databaseId);
        }

        kubeBlocksClient.delete(database.getNamespaceName(), databaseId);
        database.setStatus(DatabaseStatus.DELETING);
        database.setMessage("Database deletion was accepted by Kubernetes");
        database.setUpdatedAt(Instant.now());
        databaseRepository.save(database);
        sharedGatewayService.removeRouteAndRelease(database);
        return new DeleteDatabaseResponse(databaseId, DatabaseStatus.DELETING,
                "Database deletion was accepted by Kubernetes");
    }

    public DatabaseResponse setDeletionProtection(String project,
                                                  String databaseId, boolean enabled) {
        DatabaseMetadata metadata = requireDatabase(project, databaseId);
        DatabaseResponse response = kubeBlocksClient.setDeletionProtection(
                metadata.getNamespaceName(), databaseId, enabled);
        metadata.setDeletionProtection(enabled);
        metadata.setUpdatedAt(Instant.now());
        databaseRepository.save(metadata);
        return withPublicAccess(metadata, response);
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
                "storageOptionsGi", List.of(10, 20, 50, 100),
                "storageClass", properties.getStorageClass());
    }

    private DatabaseMetadata requireDatabase(String project, String databaseId) {
        validateProject(project);
        return databaseRepository.findByDatabaseIdAndProjectName(databaseId, project)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Database " + databaseId + " was not found in project " + project));
    }

    private DatabaseResponse fromMetadata(DatabaseMetadata database) {
        int port = kubeBlocksClient.defaultPort(database.getEngine());
        return new DatabaseResponse(database.getDatabaseId(), database.getProjectName(),
                database.getNamespaceName(), database.getDisplayName(), database.getEngine(),
                database.getMode(), database.getDatabaseVersion(), database.getSizePlan(),
                database.getStorageGi(), database.isDeletionProtection(),
                database.getStatus(), stage(database), database.getProgress(),
                database.getReplicas(), 0, 0, false,
                new PrivateEndpointResponse(null, port, false),
                publicEndpoint(database),
                database.getMessage());
    }

    private DatabaseResponse withPublicAccess(DatabaseMetadata metadata, DatabaseResponse live) {
        PublicEndpointResponse publicEndpoint = publicEndpoint(metadata);
        return new DatabaseResponse(live.databaseId(), metadata.getProjectName(),
                metadata.getNamespaceName(), live.name(),
                live.engine(), live.mode(), live.version(), live.size(), live.storageGi(),
                live.deletionProtection(), metadata.getStatus(), stage(metadata),
                metadata.getProgress(), live.replicas(),
                live.readyReplicas(), live.readyVolumes(), live.serviceReady(),
                live.privateEndpoint(), publicEndpoint, metadata.getMessage());
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

    private void syncLiveStatus(DatabaseMetadata metadata, DatabaseResponse live) {
        if (live.status() == DatabaseStatus.FAILED) {
            metadata.setStatus(DatabaseStatus.FAILED);
            metadata.setProvisioningStage(ProvisioningStage.FAILED);
            metadata.setProgress(100);
            metadata.setMessage(live.message());
        }
        metadata.setDeletionProtection(live.deletionProtection());
        metadata.setUpdatedAt(Instant.now());
        databaseRepository.save(metadata);
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
        List<String> cidrs = safeCidrs(allowedCidrs);
        if (cidrs.isEmpty())
            throw new ApiException(HttpStatus.BAD_REQUEST, "Could not determine an allowed client IP");
        if (cidrs.stream().anyMatch(cidr -> cidr == null || !CIDR.matcher(cidr).matches()))
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Every allowedCidrs value must be a valid IPv4 CIDR such as 49.50.73.146/32");
        if (cidrs.contains("0.0.0.0/0"))
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "0.0.0.0/0 is not allowed. Restrict access to customer IP ranges");
    }

    private List<String> safeCidrs(List<String> cidrs) {
        return cidrs == null ? List.of() : List.copyOf(cidrs);
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
                request.deletionProtection(), request.tags());
    }

    private void authorizeCaller(DatabaseMetadata database, String clientIp) {
        if (clientIp == null || clientIp.isBlank()) return;
        String callerCidr = clientIp + "/32";
        List<String> existing = new java.util.ArrayList<>(metadataCidrs(database));
        if (!existing.contains(callerCidr)) {
            existing.add(callerCidr);
            while (existing.size() > 10) existing.remove(0);
            database.setAllowedCidrs(existing.stream().sorted().toList().toString());
            database.setUpdatedAt(Instant.now());
            databaseRepository.save(database);
            sharedGatewayService.reconcileNow();
        }
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
                    + port + "/" + database + "?authSource=" + urlEncode(database)
                    + (publicRoute && mode != DatabaseMode.SHARDING
                    ? "&directConnection=true" : "");
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
            return get(
                    database.getProjectName(),
                    database.getDatabaseId()
            );
        } catch (ApiException exception) {
            return metadataOnlyResponse(database);
        }
    }

    private DatabaseResponse metadataOnlyResponse(
            DatabaseMetadata database
    ) {
        int port = kubeBlocksClient.defaultPort(
                database.getEngine()
        );

        return new DatabaseResponse(
                database.getDatabaseId(),
                database.getProjectName(),
                database.getNamespaceName(),
                database.getDisplayName(),
                database.getEngine(),
                database.getMode(),
                database.getDatabaseVersion(),
                database.getSizePlan(),
                database.getStorageGi(),
                database.isDeletionProtection(),
                database.getStatus(),
                stage(database),
                database.getProgress(),
                database.getReplicas(),
                0,
                0,
                false,
                new PrivateEndpointResponse(
                        null,
                        port,
                        false
                ),
                new PublicEndpointResponse(
                        null,
                        port,
                        false,
                        metadataCidrs(database)
                ),
                "Kubernetes resource was not found; showing last known metadata"
        );
    }

}
