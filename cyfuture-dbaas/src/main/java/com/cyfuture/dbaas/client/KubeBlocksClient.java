package com.cyfuture.dbaas.client;

import com.cyfuture.dbaas.config.DatabaseProperties;
import com.cyfuture.dbaas.dto.CreateDatabaseRequest;
import com.cyfuture.dbaas.dto.DatabaseResponse;
import com.cyfuture.dbaas.dto.PrivateEndpointResponse;
import com.cyfuture.dbaas.dto.PublicEndpointResponse;
import com.cyfuture.dbaas.exception.ApiException;
import com.cyfuture.dbaas.model.DatabaseEngine;
import com.cyfuture.dbaas.model.DatabaseStatus;
import com.cyfuture.dbaas.model.DatabaseMode;
import com.cyfuture.dbaas.model.SizePlan;
import com.cyfuture.dbaas.model.ProvisioningStage;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.openapi.apis.StorageV1Api;
import io.kubernetes.client.openapi.models.V1Namespace;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaim;
import io.kubernetes.client.openapi.models.V1Secret;
import io.kubernetes.client.openapi.models.V1Taint;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class KubeBlocksClient {
    private static final String GROUP = "apps.kubeblocks.io";
    private static final String VERSION = "v1";
    private static final String CLUSTERS = "clusters";
    private static final String CLUSTER_DEFINITIONS = "clusterdefinitions";

    private final DatabaseProperties properties;
    private final CustomObjectsApi customObjectsApi;
    private final CoreV1Api coreV1Api;
    private final StorageV1Api storageV1Api;

    public KubeBlocksClient(ApiClient apiClient, DatabaseProperties properties) {
        this.properties = properties;
        this.customObjectsApi = new CustomObjectsApi(apiClient);
        this.coreV1Api = new CoreV1Api(apiClient);
        this.storageV1Api = new StorageV1Api(apiClient);
    }

    public void preflight(String namespace, String project,
                          CreateDatabaseRequest request) {
        DatabaseProperties.EngineSettings settings = properties.engine(request.engine());
        try {
            ensureNamespace(namespace, project);
            storageV1Api.readStorageClass(settingsOr(properties.getStorageClass())).execute();
            customObjectsApi.getClusterCustomObject(
                    GROUP, VERSION, CLUSTER_DEFINITIONS, settings.getClusterDefinition()).execute();
            ensureHealthyWorkerExists();
        } catch (io.kubernetes.client.openapi.ApiException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Kubernetes preflight failed: " + kubernetesMessage(exception));
        }
    }

    public void create(String namespace, String project, String databaseId,
                       CreateDatabaseRequest request) {
        Map<String, Object> body = buildCluster(
                namespace, project, databaseId, request);
        try {
            customObjectsApi.createNamespacedCustomObject(
                    GROUP, VERSION, namespace, CLUSTERS, body).execute();
        } catch (io.kubernetes.client.openapi.ApiException exception) {
            // A create operation can safely resume after an application restart.
            // Kubernetes 409 means the same deterministic DBaaS resource exists.
            if (exception.getCode() == 409) return;
            cleanupPartialDeployment(namespace, databaseId);
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Database deployment was rejected and cleaned up: " + kubernetesMessage(exception));
        }
    }

    public DatabaseResponse get(String namespace, String databaseId) {
        try {
            Object object = customObjectsApi.getNamespacedCustomObject(
                    GROUP, VERSION, namespace, CLUSTERS, databaseId).execute();
            return toResponse(namespace, asMap(object));
        } catch (io.kubernetes.client.openapi.ApiException exception) {
            if (exception.getCode() == 404) {
                throw new ApiException(HttpStatus.NOT_FOUND, "Database " + databaseId + " was not found");
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY, kubernetesMessage(exception));
        }
    }

    public String adminCredentialSecretName(String namespace, String databaseId,
                                            DatabaseEngine engine)
            throws io.kubernetes.client.openapi.ApiException {
        String account = properties.engine(engine).getCredentialAccount();
        if (account == null || account.isBlank()) {
            throw new IllegalStateException("Credential account is not configured for " + engine);
        }
        String expectedSuffix = "-account-" + account;
        List<V1Secret> secrets = coreV1Api.listNamespacedSecret(namespace).execute().getItems();
        return secrets.stream()
                .filter(secret -> secret.getMetadata() != null
                        && secret.getMetadata().getName() != null
                        && secret.getMetadata().getName().startsWith(databaseId + "-")
                        && secret.getMetadata().getName().endsWith(expectedSuffix)
                        && secret.getData() != null
                        && secret.getData().containsKey("username")
                        && secret.getData().containsKey("password"))
                .map(secret -> secret.getMetadata().getName())
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                        "KubeBlocks credential Secret is not ready yet for " + databaseId));
    }

    public void delete(String namespace, String databaseId) {
        get(namespace, databaseId);
        try {
            customObjectsApi.deleteNamespacedCustomObject(
                    GROUP, VERSION, namespace, CLUSTERS, databaseId).execute();
        } catch (io.kubernetes.client.openapi.ApiException exception) {
            if (exception.getCode() == 404) {
                throw new ApiException(HttpStatus.NOT_FOUND, "Database " + databaseId + " was not found");
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "KubeBlocks could not delete the database: " + kubernetesMessage(exception));
        }
    }

    public DatabaseResponse setDeletionProtection(String namespace, String databaseId, boolean enabled) {
        try {
            Map<String, Object> cluster = asMap(customObjectsApi.getNamespacedCustomObject(
                    GROUP, VERSION, namespace, CLUSTERS, databaseId).execute());
            asMap(cluster.get("spec")).put("terminationPolicy", enabled ? "DoNotTerminate" : "Delete");
            Map<String, Object> metadata = asMap(cluster.get("metadata"));
            asMap(metadata.get("annotations")).put(
                    "dbaas.cyfuture.com/deletion-protection", String.valueOf(enabled));
            customObjectsApi.replaceNamespacedCustomObject(
                    GROUP, VERSION, namespace, CLUSTERS, databaseId, cluster).execute();
            return get(namespace, databaseId);
        } catch (io.kubernetes.client.openapi.ApiException exception) {
            if (exception.getCode() == 404) {
                throw new ApiException(HttpStatus.NOT_FOUND, "Database " + databaseId + " was not found");
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Could not update deletion protection: " + kubernetesMessage(exception));
        }
    }

    private Map<String, Object> buildCluster(String namespace, String project, String databaseId,
                                             CreateDatabaseRequest request) {
        DatabaseProperties.EngineSettings settings = properties.engine(request.engine());

        Map<String, Object> resources = Map.of(
                "requests", Map.of("cpu", request.size().cpu(), "memory", request.size().memory()),
                "limits", Map.of("cpu", request.size().cpu(), "memory", request.size().memory()));

        Map<String, Object> volume = Map.of(
                "name", "data",
                "spec", Map.of(
                        "storageClassName", properties.getStorageClass(),
                        "accessModes", List.of("ReadWriteOnce"),
                        "resources", Map.of("requests", Map.of("storage", request.storageGi() + "Gi"))));

        Map<String, Object> component = new LinkedHashMap<>();
        component.put("name", settings.getComponentName());
        component.put("serviceVersion", request.version());
        component.put("replicas", request.replicas());
        component.put("resources", resources);
        component.put("volumeClaimTemplates", List.of(volume));
        if (request.timezone() != null && !request.timezone().isBlank()) {
            component.put("env", List.of(Map.of("name", "TZ", "value", request.timezone())));
        }

        Map<String, Object> annotations = new LinkedHashMap<>();
        annotations.put("dbaas.cyfuture.com/display-name", request.name());
        annotations.put("dbaas.cyfuture.com/project", project);
        annotations.put("dbaas.cyfuture.com/engine", request.engine().name());
        annotations.put("dbaas.cyfuture.com/version", request.version());
        annotations.put("dbaas.cyfuture.com/mode", request.mode().name());
        annotations.put("dbaas.cyfuture.com/size", request.size().name());
        annotations.put("dbaas.cyfuture.com/storage-gi", String.valueOf(request.storageGi()));
        annotations.put("dbaas.cyfuture.com/replicas", String.valueOf(request.replicas()));
        annotations.put("dbaas.cyfuture.com/deletion-protection", String.valueOf(request.deletionProtection()));
        annotations.put("dbaas.cyfuture.com/remark", request.remark() == null ? "" : request.remark());
        annotations.put("dbaas.cyfuture.com/timezone", request.timezone() == null ? "" : request.timezone());
        annotations.put("dbaas.cyfuture.com/tags", encodeTags(request.tags()));
        annotations.put("dbaas.cyfuture.com/allowed-cidrs",
                String.join(",", request.allowedCidrs() == null ? List.of() : request.allowedCidrs()));

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("clusterDef", settings.getClusterDefinition());
        spec.put("topology", request.mode() == DatabaseMode.SHARDING ? "sharding" : settings.getTopology());
        spec.put("terminationPolicy", request.deletionProtection() ? "DoNotTerminate" : "Delete");

        if (request.mode() == DatabaseMode.SHARDING) {
            Map<String, Object> shard = new LinkedHashMap<>();
            shard.put("name", "shard");
            shard.put("serviceVersion", request.version());
            shard.put("replicas", request.replicas());
            shard.put("resources", resources);
            shard.put("volumeClaimTemplates", List.of(volume));

            Map<String, Object> configServer = new LinkedHashMap<>();
            configServer.put("name", "config-server");
            configServer.put("serviceVersion", request.version());
            configServer.put("replicas", 3);
            configServer.put("resources", resources);
            configServer.put("volumeClaimTemplates", List.of(volume));

            Map<String, Object> mongos = new LinkedHashMap<>();
            mongos.put("name", "mongos");
            mongos.put("serviceVersion", request.version());
            mongos.put("replicas", 2);
            mongos.put("resources", resources);

            spec.put("shardings", List.of(Map.of(
                    "name", "shard", "shards", request.shards(), "template", shard)));
            spec.put("componentSpecs", List.of(configServer, mongos));
            annotations.put("dbaas.cyfuture.com/expected-pods",
                    String.valueOf(request.shards() * request.replicas() + 5));
            annotations.put("dbaas.cyfuture.com/expected-volumes",
                    String.valueOf(request.shards() * request.replicas() + 3));
        } else {
            spec.put("componentSpecs", List.of(component));
            annotations.put("dbaas.cyfuture.com/expected-pods", String.valueOf(request.replicas()));
            annotations.put("dbaas.cyfuture.com/expected-volumes", String.valueOf(request.replicas()));
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", databaseId);
        metadata.put("namespace", namespace);
        metadata.put("labels", Map.of(
                "app.kubernetes.io/managed-by", "cyfuture-dbaas",
                "dbaas.cyfuture.com/project", project,
                "dbaas.cyfuture.com/database-id", databaseId));
        metadata.put("annotations", annotations);

        Map<String, Object> cluster = new LinkedHashMap<>();
        cluster.put("apiVersion", GROUP + "/" + VERSION);
        cluster.put("kind", "Cluster");
        cluster.put("metadata", metadata);
        cluster.put("spec", spec);
        return cluster;
    }

    private DatabaseResponse toResponse(String namespace, Map<String, Object> cluster) {
        Map<String, Object> metadata = asMap(cluster.get("metadata"));
        Map<String, Object> annotations = asMap(metadata.get("annotations"));
        Map<String, Object> spec = asMap(cluster.get("spec"));
        Map<String, Object> status = asMap(cluster.get("status"));
        List<?> components = (List<?>) spec.getOrDefault("componentSpecs", List.of());
        Map<String, Object> component = components.isEmpty() ? Map.of() : asMap(components.get(0));

        String id = String.valueOf(metadata.get("name"));
        String phase = String.valueOf(status.getOrDefault("phase", ""));
        DatabaseStatus databaseStatus = mapStatus(phase);
        int replicas = integerAnnotation(annotations, "dbaas.cyfuture.com/replicas",
                number(component.get("replicas")));
        int expectedPods = integerAnnotation(annotations, "dbaas.cyfuture.com/expected-pods", replicas);
        int expectedVolumes = integerAnnotation(annotations, "dbaas.cyfuture.com/expected-volumes", replicas);
        int readyReplicas = countReadyPods(namespace, id);
        int readyVolumes = countBoundVolumes(namespace, id);
        DatabaseEngine engine = DatabaseEngine.valueOf(String.valueOf(
                annotations.get("dbaas.cyfuture.com/engine")));
        DatabaseMode mode = DatabaseMode.valueOf(String.valueOf(
                annotations.getOrDefault("dbaas.cyfuture.com/mode", "STANDALONE")));
        String privateHost = internalHost(namespace, id, mode == DatabaseMode.SHARDING
                ? "mongos" : String.valueOf(component.get("name")));
        boolean serviceReady = privateHost != null;
        int port = defaultPort(engine);
        PrivateEndpointResponse privateEndpoint = new PrivateEndpointResponse(
                privateHost, port, serviceReady);
        PublicEndpointResponse publicEndpoint = new PublicEndpointResponse(
                null, port, false, allowedCidrs(annotations));

        if (databaseStatus == DatabaseStatus.RUNNING
                && (readyReplicas < expectedPods || readyVolumes < expectedVolumes
                || !serviceReady)) {
            databaseStatus = DatabaseStatus.PROVISIONING;
        }

        return new DatabaseResponse(
                id,
                String.valueOf(annotations.getOrDefault("dbaas.cyfuture.com/project", "unknown")),
                namespace,
                String.valueOf(annotations.getOrDefault("dbaas.cyfuture.com/display-name", id)),
                engine,
                mode,
                String.valueOf(annotations.get("dbaas.cyfuture.com/version")),
                SizePlan.valueOf(String.valueOf(annotations.getOrDefault("dbaas.cyfuture.com/size", "C1G1"))),
                integerAnnotation(annotations, "dbaas.cyfuture.com/storage-gi", 10),
                Boolean.parseBoolean(String.valueOf(annotations.getOrDefault(
                        "dbaas.cyfuture.com/deletion-protection", "false"))),
                databaseStatus,
                databaseStatus == DatabaseStatus.RUNNING
                        ? ProvisioningStage.READY : ProvisioningStage.WAITING_FOR_REPLICAS,
                databaseStatus == DatabaseStatus.RUNNING ? 100 : 45,
                replicas,
                readyReplicas,
                readyVolumes,
                serviceReady,
                privateEndpoint,
                publicEndpoint,
                readinessMessage(phase, databaseStatus, readyReplicas, expectedPods,
                        readyVolumes, expectedVolumes, serviceReady));
    }

    private int countReadyPods(String namespace, String databaseId) {
        try {
            List<V1Pod> pods = coreV1Api.listNamespacedPod(namespace)
                    .labelSelector("app.kubernetes.io/instance=" + databaseId)
                    .execute().getItems();
            return (int) pods.stream().filter(this::isReady).count();
        } catch (io.kubernetes.client.openapi.ApiException exception) {
            return 0;
        }
    }

    private boolean isReady(V1Pod pod) {
        if (pod.getStatus() == null || pod.getStatus().getConditions() == null) return false;
        return pod.getStatus().getConditions().stream()
                .anyMatch(condition -> "Ready".equals(condition.getType()) && "True".equals(condition.getStatus()));
    }

    private int countBoundVolumes(String namespace, String databaseId) {
        try {
            List<V1PersistentVolumeClaim> claims = coreV1Api
                    .listNamespacedPersistentVolumeClaim(namespace)
                    .labelSelector("app.kubernetes.io/instance=" + databaseId)
                    .execute().getItems();
            return (int) claims.stream()
                    .filter(claim -> claim.getStatus() != null
                            && "Bound".equalsIgnoreCase(claim.getStatus().getPhase()))
                    .count();
        } catch (io.kubernetes.client.openapi.ApiException exception) {
            return 0;
        }
    }

    private String internalHost(String namespace, String databaseId, String componentName) {
        if (componentName == null || componentName.isBlank() || "null".equals(componentName)) {
            return null;
        }

        // KubeBlocks add-ons use one of these two service-name patterns.
        List<String> serviceNames = List.of(
                databaseId + "-" + componentName,
                databaseId + "-" + componentName + "-" + componentName);

        for (String serviceName : serviceNames) {
            try {
                coreV1Api.readNamespacedService(serviceName, namespace).execute();
                return serviceName + "." + namespace + ".svc.cluster.local";
            } catch (io.kubernetes.client.openapi.ApiException exception) {
                if (exception.getCode() != 404) return null;
            }
        }
        return null;
    }

    private List<String> allowedCidrs(Map<String, Object> annotations) {
        String value = String.valueOf(
                annotations.getOrDefault("dbaas.cyfuture.com/allowed-cidrs", ""));
        return value.isBlank() ? List.of() : List.of(value.split(","));
    }

    private void cleanupPartialDeployment(String namespace, String databaseId) {
        try {
            Map<String, Object> cluster = asMap(customObjectsApi.getNamespacedCustomObject(
                    GROUP, VERSION, namespace, CLUSTERS, databaseId).execute());
            asMap(cluster.get("spec")).put("terminationPolicy", "Delete");
            customObjectsApi.replaceNamespacedCustomObject(
                    GROUP, VERSION, namespace, CLUSTERS, databaseId, cluster).execute();
            customObjectsApi.deleteNamespacedCustomObject(
                    GROUP, VERSION, namespace, CLUSTERS, databaseId).execute();
        } catch (Exception ignored) {
            // The original Kubernetes error is returned to the API caller.
        }
    }

    private void ensureHealthyWorkerExists() throws io.kubernetes.client.openapi.ApiException {
        if (healthyWorkerCount() == 0) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "No Ready and schedulable worker node is available without resource pressure");
        }
    }

    private int healthyWorkerCount() throws io.kubernetes.client.openapi.ApiException {
        return (int) coreV1Api.listNode().execute().getItems().stream()
                .filter(node -> node.getSpec() != null
                        && !Boolean.TRUE.equals(node.getSpec().getUnschedulable())
                        && acceptsDatabasePods(node.getSpec().getTaints())
                        && node.getStatus() != null
                        && node.getStatus().getConditions() != null
                        && node.getStatus().getConditions().stream().anyMatch(condition ->
                                "Ready".equals(condition.getType()) && "True".equals(condition.getStatus()))
                        && node.getStatus().getConditions().stream().noneMatch(condition ->
                                ("MemoryPressure".equals(condition.getType())
                                        || "DiskPressure".equals(condition.getType())
                                        || "PIDPressure".equals(condition.getType()))
                                        && "True".equals(condition.getStatus())))
                .count();
    }

    private boolean acceptsDatabasePods(List<V1Taint> taints) {
        if (taints == null) return true;
        return taints.stream().noneMatch(taint -> "NoSchedule".equals(taint.getEffect())
                || "NoExecute".equals(taint.getEffect()));
    }

    public int defaultPort(DatabaseEngine engine) {
        return switch (engine) {
            case POSTGRESQL -> 5432;
            case MYSQL -> 3306;
            case MONGODB -> 27017;
        };
    }

    private void ensureNamespace(String namespace, String project)
            throws io.kubernetes.client.openapi.ApiException {
        try {
            validateNamespaceOwnership(
                    coreV1Api.readNamespace(namespace).execute(), project);
        } catch (io.kubernetes.client.openapi.ApiException exception) {
            if (exception.getCode() != 404) throw exception;
            try {
                V1Namespace projectNamespace = new V1Namespace()
                        .metadata(new V1ObjectMeta()
                                .name(namespace)
                                .labels(Map.of(
                                        "app.kubernetes.io/managed-by", "cyfuture-dbaas",
                                        "dbaas.cyfuture.com/project", project)));
                coreV1Api.createNamespace(projectNamespace).execute();
            } catch (io.kubernetes.client.openapi.ApiException createException) {
                if (createException.getCode() != 409) throw createException;
                validateNamespaceOwnership(
                        coreV1Api.readNamespace(namespace).execute(), project);
            }
        }
    }

    private void validateNamespaceOwnership(V1Namespace namespace, String project) {
        Map<String, String> labels = namespace.getMetadata() == null
                ? Map.of() : namespace.getMetadata().getLabels();
        if (labels == null
                || !"cyfuture-dbaas".equals(labels.get("app.kubernetes.io/managed-by"))
                || !project.equals(labels.get("dbaas.cyfuture.com/project"))) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Namespace already exists but is not owned by DBaaS project " + project);
        }
    }

    private DatabaseStatus mapStatus(String phase) {
        if ("Running".equalsIgnoreCase(phase)) return DatabaseStatus.RUNNING;
        if ("Failed".equalsIgnoreCase(phase) || "Abnormal".equalsIgnoreCase(phase)) return DatabaseStatus.FAILED;
        if (phase.isBlank()) return DatabaseStatus.PROVISIONING;
        return DatabaseStatus.PROVISIONING;
    }

    private String readinessMessage(String phase, DatabaseStatus status,
                                    int readyReplicas, int replicas, int readyVolumes,
                                    int expectedVolumes,
                                    boolean serviceReady) {
        if (status == DatabaseStatus.FAILED) return "KubeBlocks phase: " + phase;
        if (readyReplicas < replicas) return "Waiting for database Pods: " + readyReplicas + "/" + replicas;
        if (readyVolumes < expectedVolumes) return "Waiting for persistent volumes: "
                + readyVolumes + "/" + expectedVolumes;
        if (!serviceReady) return "Waiting for the internal database Service";
        if (status == DatabaseStatus.RUNNING) return "Database is ready";
        return phase.isBlank() ? "KubeBlocks is processing the request" : "KubeBlocks phase: " + phase;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private int integerAnnotation(Map<String, Object> annotations, String key, int fallback) {
        try {
            return Integer.parseInt(String.valueOf(annotations.getOrDefault(key, fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String encodeTags(Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) return "";
        return tags.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }

    private String kubernetesMessage(io.kubernetes.client.openapi.ApiException exception) {
        return exception.getResponseBody() == null ? exception.getMessage() : exception.getResponseBody();
    }

    private String settingsOr(String value) {
        if (value == null || value.isBlank()) throw new IllegalStateException("StorageClass is not configured");
        return value;
    }
}
