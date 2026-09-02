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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class KubeBlocksClient {
    private static final String GROUP = "apps.kubeblocks.io";
    private static final String VERSION = "v1";
    private static final String CLUSTERS = "clusters";
    private static final String CLUSTER_DEFINITIONS = "clusterdefinitions";
    private static final String OPS_GROUP = "operations.kubeblocks.io";
    private static final String OPS_VERSION = "v1alpha1";
    private static final String OPS_REQUESTS = "opsrequests";
    private static final String OPS_REQUEST_CRD = "opsrequests.operations.kubeblocks.io";
    private static final Pattern QUANTITY = Pattern.compile("^([1-9][0-9]*)(Mi|Gi|Ti)$");
    private static final String STRICT_IN_PLACE = "StrictInPlace";

    private final DatabaseProperties properties;
    private final CustomObjectsApi customObjectsApi;
    private final CoreV1Api coreV1Api;
    private final StorageV1Api storageV1Api;
    private final Set<String> verifiedOpsRequestFields = new HashSet<>();

    @Autowired
    public KubeBlocksClient(ApiClient apiClient, DatabaseProperties properties) {
        this.properties = properties;
        this.customObjectsApi = new CustomObjectsApi(apiClient);
        this.coreV1Api = new CoreV1Api(apiClient);
        this.storageV1Api = new StorageV1Api(apiClient);
    }

    KubeBlocksClient(DatabaseProperties properties, CustomObjectsApi customObjectsApi,
                     CoreV1Api coreV1Api, StorageV1Api storageV1Api) {
        this.properties = properties;
        this.customObjectsApi = customObjectsApi;
        this.coreV1Api = coreV1Api;
        this.storageV1Api = storageV1Api;
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

    // This method will delete the namespaces
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

    public void requestDelete(String namespace, String databaseId) {
        try {
            customObjectsApi.deleteNamespacedCustomObject(
                    GROUP, VERSION, namespace, CLUSTERS, databaseId).execute();
        } catch (io.kubernetes.client.openapi.ApiException exception) {
            if (exception.getCode() == 404) return;
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "KubeBlocks could not delete the database: " + kubernetesMessage(exception));
        }
    }

    public ClusterObservation observeCluster(String namespace, String databaseId) {
        try {
            Map<String, Object> cluster = asMap(customObjectsApi.getNamespacedCustomObject(
                    GROUP, VERSION, namespace, CLUSTERS, databaseId).execute());
            return observation(namespace, cluster);
        } catch (io.kubernetes.client.openapi.ApiException exception) {
            if (exception.getCode() == 404) {
                return ClusterObservation.missing(namespace, databaseId);
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Could not observe KubeBlocks Cluster: " + kubernetesMessage(exception));
        }
    }

    public List<ManagedClusterSummary> listManagedClusters() {
        try {
            Map<String, Object> list = asMap(customObjectsApi.listClusterCustomObject(
                    GROUP, VERSION, CLUSTERS).execute());
            List<ManagedClusterSummary> result = new ArrayList<>();
            for (Object item : (List<?>) list.getOrDefault("items", List.of())) {
                Map<String, Object> cluster = asMap(item);
                Map<String, Object> metadata = asMap(cluster.get("metadata"));
                Map<String, Object> labels = asMap(metadata.get("labels"));
                if (!"cyfuture-dbaas".equals(labels.get("app.kubernetes.io/managed-by"))) {
                    continue;
                }
                Map<String, Object> annotations = asMap(metadata.get("annotations"));
                Map<String, Object> status = asMap(cluster.get("status"));
                result.add(new ManagedClusterSummary(
                        String.valueOf(metadata.get("namespace")),
                        String.valueOf(metadata.get("name")),
                        String.valueOf(labels.getOrDefault("dbaas.cyfuture.com/database-id",
                                metadata.get("name"))),
                        String.valueOf(labels.getOrDefault("dbaas.cyfuture.com/project",
                                annotations.getOrDefault("dbaas.cyfuture.com/project", ""))),
                        String.valueOf(labels.getOrDefault("dbaas.cyfuture.com/engine",
                                annotations.getOrDefault("dbaas.cyfuture.com/engine", ""))),
                        String.valueOf(status.getOrDefault("phase", "Unknown")),
                        latestConditionMessage(status)));
            }
            return result;
        } catch (io.kubernetes.client.openapi.ApiException exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Could not list managed KubeBlocks Clusters: " + kubernetesMessage(exception));
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

    public List<ClusterComponentInfo> components(String namespace, String databaseId) {
        Map<String, Object> cluster = cluster(namespace, databaseId);
        Map<String, Object> spec = asMap(cluster.get("spec"));
        List<ClusterComponentInfo> result = new ArrayList<>();

        for (Object item : (List<?>) spec.getOrDefault("componentSpecs", List.of())) {
            Map<String, Object> component = asMap(item);
            result.add(componentInfo(component, false));
        }
        for (Object item : (List<?>) spec.getOrDefault("shardings", List.of())) {
            Map<String, Object> sharding = asMap(item);
            Map<String, Object> template = asMap(sharding.get("template"));
            if (!template.isEmpty()) {
                int shards = number(sharding.get("shards"));
                ClusterComponentInfo info = componentInfo(template, true);
                result.add(new ClusterComponentInfo(info.name(), info.replicas(),
                        shards, true, info.storage(), info.podUpdatePolicy()));
            }
        }
        return result;
    }

    public ClusterComponentInfo requireComponent(String namespace, String databaseId,
                                                 String requestedComponentName) {
        List<ClusterComponentInfo> components = components(namespace, databaseId);
        if (requestedComponentName == null || requestedComponentName.isBlank()) {
            if (components.size() == 1) return components.get(0);
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "componentName is required. Valid components: " + componentNames(components));
        }
        return components.stream()
                .filter(component -> component.name().equals(requestedComponentName))
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                        "Unknown componentName " + requestedComponentName
                                + ". Valid components: " + componentNames(components)));
    }

    public List<String> componentNames(String namespace, String databaseId) {
        return componentNames(components(namespace, databaseId));
    }

    public void ensureStrictInPlacePodUpdatePolicy(String namespace, String databaseId,
                                                   String componentName) {
        try {
            Map<String, Object> cluster = cluster(namespace, databaseId);
            if (!setStrictInPlace(cluster, componentName)) return;
            customObjectsApi.replaceNamespacedCustomObject(
                    GROUP, VERSION, namespace, CLUSTERS, databaseId, cluster).execute();
        } catch (io.kubernetes.client.openapi.ApiException exception) {
            if (exception.getCode() == 404) {
                throw new ApiException(HttpStatus.NOT_FOUND,
                        "Database " + databaseId + " was not found");
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Could not enable in-place vertical scaling: " + kubernetesMessage(exception));
        }
    }

    public void createVerticalScalingOpsRequest(String namespace, String databaseId,
                                                String operationId, String componentName,
                                                Map<String, String> requests,
                                                Map<String, String> limits) {
        createOpsRequest(namespace, operationId, "VerticalScaling",
                "verticalScaling", List.of(Map.of(
                        "componentName", componentName,
                        "requests", requests,
                        "limits", limits)), databaseId);
    }

    public void createHorizontalScalingOpsRequest(String namespace, String databaseId,
                                                  String operationId, String componentName,
                                                  int currentReplicas,
                                                  int targetReplicas) {
        Map<String, Object> scaling = new LinkedHashMap<>();
        scaling.put("componentName", componentName);
        if (targetReplicas > currentReplicas) {
            scaling.put("scaleOut", Map.of("replicaChanges", targetReplicas - currentReplicas));
        } else if (targetReplicas < currentReplicas) {
            scaling.put("scaleIn", Map.of("replicaChanges", currentReplicas - targetReplicas));
        } else {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "targetReplicas must be different from the current replica count");
        }
        createOpsRequest(namespace, operationId, "HorizontalScaling",
                "horizontalScaling", List.of(scaling), databaseId);
    }

    public void createVolumeExpansionOpsRequest(String namespace, String databaseId,
                                                String operationId, String componentName,
                                                String volumeName,
                                                String newStorageSize) {
        createOpsRequest(namespace, operationId, "VolumeExpansion",
                "volumeExpansion", List.of(Map.of(
                        "componentName", componentName,
                        "volumeClaimTemplates", List.of(Map.of(
                                "name", volumeName,
                                "storage", newStorageSize)))), databaseId);
    }

    public void createRestartOpsRequest(String namespace, String databaseId,
                                        String operationId,
                                        List<String> componentNames) {
        createOpsRequest(namespace, operationId, "Restart",
                "restart", componentNames.stream()
                        .map(component -> Map.of("componentName", component))
                        .toList(), databaseId);
    }

    public OpsRequestInfo getOpsRequest(String namespace, String opsRequestName) {
        try {
            Map<String, Object> object = asMap(customObjectsApi.getNamespacedCustomObject(
                    OPS_GROUP, OPS_VERSION, namespace, OPS_REQUESTS, opsRequestName).execute());
            Map<String, Object> status = asMap(object.get("status"));
            return new OpsRequestInfo(String.valueOf(status.getOrDefault("phase", "Pending")),
                    String.valueOf(status.getOrDefault("progress", "-/-")),
                    lastConditionMessage(status),
                    instant(status.get("startTimestamp")),
                    instant(status.get("completionTimestamp")));
        } catch (io.kubernetes.client.openapi.ApiException exception) {
            if (exception.getCode() == 404) {
                return new OpsRequestInfo("Pending", "-/-",
                        "Waiting for KubeBlocks OpsRequest submission", null, null);
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Could not read KubeBlocks OpsRequest: " + kubernetesMessage(exception));
        }
    }

    public long storageBytes(String quantity) {
        Matcher matcher = QUANTITY.matcher(quantity == null ? "" : quantity);
        if (!matcher.matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Storage size must be a Kubernetes quantity such as 30Gi");
        }
        BigInteger value = new BigInteger(matcher.group(1));
        BigInteger multiplier = switch (matcher.group(2)) {
            case "Mi" -> BigInteger.valueOf(1024L * 1024L);
            case "Gi" -> BigInteger.valueOf(1024L * 1024L * 1024L);
            case "Ti" -> BigInteger.valueOf(1024L * 1024L * 1024L * 1024L);
            default -> throw new IllegalArgumentException("Unsupported unit");
        };
        return value.multiply(multiplier).longValueExact();
    }

    public int storageGi(String quantity) {
        long bytes = storageBytes(quantity);
        long gib = 1024L * 1024L * 1024L;
        return Math.toIntExact(bytes / gib);
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
        component.put("podUpdatePolicy", STRICT_IN_PLACE);
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
            shard.put("podUpdatePolicy", STRICT_IN_PLACE);
            shard.put("resources", resources);
            shard.put("volumeClaimTemplates", List.of(volume));

            Map<String, Object> configServer = new LinkedHashMap<>();
            configServer.put("name", "config-server");
            configServer.put("serviceVersion", request.version());
            configServer.put("replicas", 3);
            configServer.put("podUpdatePolicy", STRICT_IN_PLACE);
            configServer.put("resources", resources);
            configServer.put("volumeClaimTemplates", List.of(volume));

            Map<String, Object> mongos = new LinkedHashMap<>();
            mongos.put("name", "mongos");
            mongos.put("serviceVersion", request.version());
            mongos.put("replicas", 2);
            mongos.put("podUpdatePolicy", STRICT_IN_PLACE);
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
                "dbaas.cyfuture.com/engine", request.engine().name(),
                "dbaas.cyfuture.com/database-id", databaseId));
        metadata.put("annotations", annotations);

        Map<String, Object> cluster = new LinkedHashMap<>();
        cluster.put("apiVersion", GROUP + "/" + VERSION);
        cluster.put("kind", "Cluster");
        cluster.put("metadata", metadata);
        cluster.put("spec", spec);
        return cluster;
    }

    private Map<String, Object> cluster(String namespace, String databaseId) {
        try {
            return asMap(customObjectsApi.getNamespacedCustomObject(
                    GROUP, VERSION, namespace, CLUSTERS, databaseId).execute());
        } catch (io.kubernetes.client.openapi.ApiException exception) {
            if (exception.getCode() == 404) {
                throw new ApiException(HttpStatus.NOT_FOUND, "Database " + databaseId + " was not found");
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY, kubernetesMessage(exception));
        }
    }

    private ClusterObservation observation(String namespace, Map<String, Object> cluster) {
        Map<String, Object> metadata = asMap(cluster.get("metadata"));
        Map<String, Object> annotations = asMap(metadata.get("annotations"));
        Map<String, Object> status = asMap(cluster.get("status"));
        String databaseId = String.valueOf(metadata.get("name"));
        String phase = String.valueOf(status.getOrDefault("phase", ""));
        int expected = integerAnnotation(annotations, "dbaas.cyfuture.com/expected-pods",
                expectedReplicas(cluster));
        int ready = countReadyPods(namespace, databaseId);
        boolean serviceReady = serviceReady(namespace, databaseId, cluster);
        return new ClusterObservation(true, namespace, databaseId, phase,
                ready, expected, serviceReady, latestConditionMessage(status));
    }

    private int expectedReplicas(Map<String, Object> cluster) {
        Map<String, Object> spec = asMap(cluster.get("spec"));
        int expected = 0;
        for (Object item : (List<?>) spec.getOrDefault("componentSpecs", List.of())) {
            expected += number(asMap(item).get("replicas"));
        }
        for (Object item : (List<?>) spec.getOrDefault("shardings", List.of())) {
            Map<String, Object> sharding = asMap(item);
            expected += number(sharding.get("shards"))
                    * number(asMap(sharding.get("template")).get("replicas"));
        }
        return expected;
    }

    private boolean serviceReady(String namespace, String databaseId, Map<String, Object> cluster) {
        Map<String, Object> spec = asMap(cluster.get("spec"));
        List<?> components = (List<?>) spec.getOrDefault("componentSpecs", List.of());
        List<?> shardings = (List<?>) spec.getOrDefault("shardings", List.of());
        String componentName = null;
        if (!shardings.isEmpty()) {
            componentName = "mongos";
        } else if (!components.isEmpty()) {
            componentName = String.valueOf(asMap(components.get(0)).get("name"));
        }
        return internalHost(namespace, databaseId, componentName) != null;
    }

    private ClusterComponentInfo componentInfo(Map<String, Object> component, boolean sharding) {
        Map<String, String> storage = new LinkedHashMap<>();
        for (Object item : (List<?>) component.getOrDefault("volumeClaimTemplates", List.of())) {
            Map<String, Object> template = asMap(item);
            String name = String.valueOf(template.get("name"));
            String size = String.valueOf(asMap(asMap(asMap(template.get("spec"))
                    .get("resources")).get("requests")).get("storage"));
            if (!name.isBlank() && !size.isBlank() && !"null".equals(size)) {
                storage.put(name, size);
            }
        }
        return new ClusterComponentInfo(String.valueOf(component.get("name")),
                number(component.get("replicas")), 0, sharding, storage,
                String.valueOf(component.getOrDefault("podUpdatePolicy", "PreferInPlace")));
    }

    private boolean setStrictInPlace(Map<String, Object> cluster, String componentName) {
        Map<String, Object> spec = asMap(cluster.get("spec"));
        boolean changed = false;
        for (Object item : (List<?>) spec.getOrDefault("componentSpecs", List.of())) {
            Map<String, Object> component = asMap(item);
            if (componentName.equals(component.get("name"))
                    && !STRICT_IN_PLACE.equals(component.get("podUpdatePolicy"))) {
                component.put("podUpdatePolicy", STRICT_IN_PLACE);
                changed = true;
            }
        }
        for (Object item : (List<?>) spec.getOrDefault("shardings", List.of())) {
            Map<String, Object> template = asMap(asMap(item).get("template"));
            if (componentName.equals(template.get("name"))
                    && !STRICT_IN_PLACE.equals(template.get("podUpdatePolicy"))) {
                template.put("podUpdatePolicy", STRICT_IN_PLACE);
                changed = true;
            }
        }
        return changed;
    }

    private List<String> componentNames(List<ClusterComponentInfo> components) {
        return components.stream().map(ClusterComponentInfo::name).toList();
    }

    private void createOpsRequest(String namespace, String operationId, String type,
                                  String operationField, List<?> operationPayload,
                                  String databaseId) {
        ensureOpsRequestSchemaSupports(operationField);
        Map<String, Object> labels = Map.of(
                "app.kubernetes.io/managed-by", "cyfuture-dbaas",
                "dbaas.cyfuture.com/database-id", databaseId,
                "dbaas.cyfuture.com/operation-id", operationId);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", operationId);
        metadata.put("namespace", namespace);
        metadata.put("labels", labels);

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("clusterName", databaseId);
        spec.put("type", type);
        spec.put(operationField, operationPayload);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("apiVersion", OPS_GROUP + "/" + OPS_VERSION);
        body.put("kind", "OpsRequest");
        body.put("metadata", metadata);
        body.put("spec", spec);

        try {
            customObjectsApi.createNamespacedCustomObject(
                    OPS_GROUP, OPS_VERSION, namespace, OPS_REQUESTS, body).execute();
        } catch (io.kubernetes.client.openapi.ApiException exception) {
            if (exception.getCode() == 409) return;
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "KubeBlocks OpsRequest was rejected: " + kubernetesMessage(exception));
        }
    }

    private void ensureOpsRequestSchemaSupports(String operationField) {
        synchronized (verifiedOpsRequestFields) {
            if (verifiedOpsRequestFields.contains(operationField)) return;
        }
        try {
            Map<String, Object> crd = asMap(customObjectsApi.getClusterCustomObject(
                    "apiextensions.k8s.io", "v1", "customresourcedefinitions",
                    OPS_REQUEST_CRD).execute());
            Map<String, Object> specProperties = opsRequestSpecProperties(crd);
            if (!specProperties.containsKey("clusterName")
                    || !specProperties.containsKey("type")
                    || !specProperties.containsKey(operationField)) {
                throw new ApiException(HttpStatus.BAD_GATEWAY,
                        "Installed KubeBlocks OpsRequest CRD does not support " + operationField);
            }
            synchronized (verifiedOpsRequestFields) {
                verifiedOpsRequestFields.add(operationField);
            }
        } catch (io.kubernetes.client.openapi.ApiException exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "KubeBlocks OpsRequest CRD is not available: " + kubernetesMessage(exception));
        }
    }

    private Map<String, Object> opsRequestSpecProperties(Map<String, Object> crd) {
        List<?> versions = (List<?>) asMap(crd.get("spec")).getOrDefault("versions", List.of());
        for (Object item : versions) {
            Map<String, Object> version = asMap(item);
            if (OPS_VERSION.equals(version.get("name"))) {
                Map<String, Object> schema = asMap(version.get("schema"));
                Map<String, Object> openApi = asMap(schema.get("openAPIV3Schema"));
                Map<String, Object> properties = asMap(openApi.get("properties"));
                Map<String, Object> spec = asMap(properties.get("spec"));
                return asMap(spec.get("properties"));
            }
        }
        return Map.of();
    }

    private String lastConditionMessage(Map<String, Object> status) {
        return latestConditionMessage(status);
    }

    private String latestConditionMessage(Map<String, Object> status) {
        List<?> conditions = (List<?>) status.getOrDefault("conditions", List.of());
        if (conditions.isEmpty()) return "KubeBlocks is processing the operation";
        Map<String, Object> condition = asMap(conditions.get(conditions.size() - 1));
        String message = String.valueOf(condition.getOrDefault("message", ""));
        if (!message.isBlank()) return message;
        return String.valueOf(condition.getOrDefault("reason", "KubeBlocks is processing the operation"));
    }

    private Instant instant(Object value) {
        if (value == null) return null;
        try {
            return Instant.parse(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
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
        int replicas = number(component.get("replicas"));
        if (replicas == 0) {
            replicas = integerAnnotation(annotations, "dbaas.cyfuture.com/replicas", 1);
        }
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
                storageGiFromComponent(component, annotations),
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

    private int storageGiFromComponent(Map<String, Object> component,
                                       Map<String, Object> annotations) {
        String storage = null;
        List<?> volumes = (List<?>) component.getOrDefault("volumeClaimTemplates", List.of());
        if (!volumes.isEmpty()) {
            Map<String, Object> volume = asMap(volumes.get(0));
            storage = String.valueOf(asMap(asMap(asMap(volume.get("spec"))
                    .get("resources")).get("requests")).get("storage"));
        }
        if (storage != null && !storage.isBlank() && !"null".equals(storage)) {
            try {
                return storageGi(storage);
            } catch (Exception ignored) {
                // Fall back to DBaaS annotation for older or custom quantities.
            }
        }
        return integerAnnotation(annotations, "dbaas.cyfuture.com/storage-gi", 10);
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

    public record ClusterComponentInfo(
            String name,
            int replicas,
            int shards,
            boolean sharding,
            Map<String, String> storage,
            String podUpdatePolicy
    ) {
        public String storage(String volumeName) {
            return storage.get(volumeName);
        }
    }

    public record OpsRequestInfo(
            String phase,
            String progress,
            String message,
            Instant startedAt,
            Instant completedAt
    ) {}

    public record ClusterObservation(
            boolean exists,
            String namespace,
            String databaseId,
            String phase,
            int readyReplicas,
            int expectedReplicas,
            boolean serviceReady,
            String message
    ) {
        public static ClusterObservation missing(String namespace, String databaseId) {
            return new ClusterObservation(false, namespace, databaseId, "Missing",
                    0, 0, false, "KubeBlocks Cluster was not found");
        }

        public boolean healthy() {
            return exists && "Running".equalsIgnoreCase(phase)
                    && expectedReplicas > 0
                    && readyReplicas >= expectedReplicas
                    && serviceReady;
        }
    }

    public record ManagedClusterSummary(
            String namespace,
            String name,
            String databaseId,
            String project,
            String engine,
            String phase,
            String message
    ) {}
}
