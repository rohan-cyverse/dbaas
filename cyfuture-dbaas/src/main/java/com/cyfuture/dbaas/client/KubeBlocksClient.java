package com.cyfuture.dbaas.client;

import com.cyfuture.dbaas.config.DatabaseProperties;
import com.cyfuture.dbaas.dto.BackupRepositoryResponse;
import com.cyfuture.dbaas.dto.CreateDatabaseRequest;
import com.cyfuture.dbaas.exception.ApiException;
import com.cyfuture.dbaas.model.DatabaseEngine;
import com.cyfuture.dbaas.model.DatabaseStatus;
import com.cyfuture.dbaas.model.DatabaseMode;
import com.cyfuture.dbaas.model.SizePlan;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.openapi.apis.StorageV1Api;
import io.kubernetes.client.openapi.models.V1Namespace;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1OwnerReference;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaim;
import io.kubernetes.client.openapi.models.V1Secret;
import io.kubernetes.client.openapi.models.V1Taint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class KubeBlocksClient {
    private static final String GROUP = "apps.kubeblocks.io";
    private static final String VERSION = "v1";
    private static final String CLUSTERS = "clusters";
    private static final String CLUSTER_CRD = "clusters.apps.kubeblocks.io";
    private static final String CLUSTER_DEFINITIONS = "clusterdefinitions";
    private static final String OPS_GROUP = "operations.kubeblocks.io";
    private static final String OPS_VERSION = "v1alpha1";
    private static final String OPS_REQUESTS = "opsrequests";
    private static final String OPS_REQUEST_CRD = "opsrequests.operations.kubeblocks.io";
    private static final String DATA_PROTECTION_GROUP = "dataprotection.kubeblocks.io";
    private static final String DATA_PROTECTION_VERSION = "v1alpha1";
    private static final String BACKUPS = "backups";
    private static final String RESTORES = "restores";
    private static final String BACKUP_POLICIES = "backuppolicies";
    private static final String BACKUP_POLICY_TEMPLATES = "backuppolicytemplates";
    private static final String BACKUP_SCHEDULES = "backupschedules";
    private static final String BACKUP_REPOS = "backuprepos";
    private static final String APP_INSTANCE_LABEL = "app.kubernetes.io/instance";
    private static final String MANAGED_BY_LABEL = "app.kubernetes.io/managed-by";
    private static final String PROJECT_LABEL = "dbaas.cyfuture.com/project";
    private static final String DATABASE_LABEL = "dbaas.cyfuture.com/database-id";
    private static final String BACKUP_ID_LABEL = "dbaas.cyfuture.com/backup-id";
    private static final String OPERATION_ID_LABEL = "dbaas.cyfuture.com/operation-id";
    /*
     * KubeBlocks 1.0 uses is-default-policy. Keep the earlier spelling as a
     * compatibility fallback for clusters upgraded from older releases.
     */
    private static final List<String> DEFAULT_BACKUP_POLICY_ANNOTATIONS = List.of(
            "dataprotection.kubeblocks.io/is-default-policy",
            "dataprotection.kubeblocks.io/is-default-backup-policy");
    private static final Pattern QUANTITY = Pattern.compile("^([1-9][0-9]*)(Mi|Gi|Ti)$");
    private static final Pattern SENSITIVE_BACKUP_DIAGNOSTIC = Pattern.compile(
            "(?i)(\\b(secret|password|passwd|pwd|token|access[_-]?key|credential|passphrase|"
                    + "encryption|kms)\\b|s3://|\\.svc\\.cluster\\.local\\b|"
                    + "\\b(?:10|127)\\.(?:\\d{1,3}\\.){2}\\d{1,3}\\b|"
                    + "\\b192\\.168\\.(?:\\d{1,3}\\.)?\\d{1,3}\\b|"
                    + "\\b172\\.(?:1[6-9]|2\\d|3[01])\\.(?:\\d{1,3}\\.)?\\d{1,3}\\b|"
                    + "\\bnamespace\\b)");
    /** Lets KubeBlocks use in-place resize when available and safely recreate Pods otherwise. */
    private static final String PREFER_IN_PLACE = "PreferInPlace";

    private final DatabaseProperties properties;
    private final CustomObjectsApi customObjectsApi;
    private final CoreV1Api coreV1Api;
    private final StorageV1Api storageV1Api;
    private final Set<String> verifiedOpsRequestFields = new HashSet<>();
    private final Set<String> verifiedRestoreFields = new HashSet<>();
    private boolean clusterBackupSchemaVerified;

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

    public DatabaseObservation get(String namespace, String databaseId) {
        try {
            Object object = customObjectsApi.getNamespacedCustomObject(
                    GROUP, VERSION, namespace, CLUSTERS, databaseId).execute();
            return toObservation(namespace, asMap(object));
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

    public void requestDelete(String namespace, String databaseId) {
        try {
            Map<String, Object> cluster = new LinkedHashMap<>(asMap(
                    customObjectsApi.getNamespacedCustomObject(
                            GROUP, VERSION, namespace, CLUSTERS, databaseId).execute()));
            // KubeBlocks' Delete policy removes the Cluster but deliberately
            // retains its PVCs. A DBaaS database delete is destructive, so use
            // WipeOut to remove the cluster-owned storage as well.
            mutableChildMap(cluster, "spec").put("terminationPolicy", "WipeOut");
            Map<String, Object> metadata = mutableChildMap(cluster, "metadata");
            Map<String, Object> annotations = mutableChildMap(metadata, "annotations");
            annotations.put("dbaas.cyfuture.com/deletion-protection", "false");
            customObjectsApi.replaceNamespacedCustomObject(
                    GROUP, VERSION, namespace, CLUSTERS, databaseId, cluster).execute();
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

    public DatabaseObservation setDeletionProtection(String namespace, String databaseId, boolean enabled) {
        try {
            updateDeletionProtection(namespace, databaseId, enabled);
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

    public void ensurePreferInPlacePodUpdatePolicy(String namespace, String databaseId,
                                                   String componentName) {
        try {
            Map<String, Object> cluster = cluster(namespace, databaseId);
            if (!setPreferInPlace(cluster, componentName)) return;
            customObjectsApi.replaceNamespacedCustomObject(
                    GROUP, VERSION, namespace, CLUSTERS, databaseId, cluster).execute();
        } catch (io.kubernetes.client.openapi.ApiException exception) {
            if (exception.getCode() == 404) {
                throw new ApiException(HttpStatus.NOT_FOUND,
                        "Database " + databaseId + " was not found");
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Could not set PreferInPlace vertical scaling policy: " + kubernetesMessage(exception));
        }
    }

    /**
     * A project delete is an explicit request to destroy every database in its
     * namespace. Clear a database-level protection first so the KubeBlocks
     * finalizer can remove the Cluster instead of holding namespace deletion.
     */
    public void prepareProjectDatabaseDeletion(String namespace, String databaseId) {
        requestDelete(namespace, databaseId);
    }

    /** Ensures the namespace for a DBaaS project exists and is owned by that project. */
    public void ensureProjectNamespace(String namespace, String project) {
        try {
            ensureNamespace(namespace, project);
        } catch (io.kubernetes.client.openapi.ApiException exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Could not create or verify project namespace: " + kubernetesMessage(exception));
        }
    }

    /** Deletes only a namespace proven to belong to the requested DBaaS project. */
    public void deleteProjectNamespace(String namespace, String project) {
        try {
            V1Namespace existing = coreV1Api.readNamespace(namespace).execute();
            validateNamespaceOwnership(existing, project);
            coreV1Api.deleteNamespace(namespace).execute();
        } catch (io.kubernetes.client.openapi.ApiException exception) {
            // A repeated project delete is safe after Kubernetes has already
            // removed the namespace.
            if (exception.getCode() == 404) return;
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Could not delete project namespace: " + kubernetesMessage(exception));
        }
    }

    /** Returns false only after Kubernetes has fully removed the owned namespace. */
    public boolean projectNamespaceExists(String namespace, String project) {
        try {
            validateNamespaceOwnership(coreV1Api.readNamespace(namespace).execute(), project);
            return true;
        } catch (io.kubernetes.client.openapi.ApiException exception) {
            if (exception.getCode() == 404) return false;
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Could not verify project namespace: " + kubernetesMessage(exception));
        }
    }

    /** Owner reference for DB-specific helper resources; never use this for shared services. */
    public V1OwnerReference clusterOwnerReference(String namespace, String databaseId) {
        Map<String, Object> cluster = cluster(namespace, databaseId);
        Map<String, Object> metadata = asMap(cluster.get("metadata"));
        String uid = String.valueOf(metadata.get("uid"));
        if (uid.isBlank() || "null".equals(uid)) {
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "KubeBlocks Cluster " + databaseId + " has no UID for helper ownership");
        }
        return new V1OwnerReference()
                .apiVersion(GROUP + "/" + VERSION)
                .kind("Cluster")
                .name(databaseId)
                .uid(uid)
                .controller(false)
                .blockOwnerDeletion(false);
    }

    /**
     * Patches all DBaaS-owned Cluster CRs that were created by older releases
     * with StrictInPlace. This only changes the update policy; KubeBlocks still
     * owns the replication-aware rollout.
     */
    public int migrateManagedClustersToPreferInPlace() {
        try {
            Map<String, Object> list = asMap(customObjectsApi.listClusterCustomObject(
                    GROUP, VERSION, CLUSTERS).execute());
            int migrated = 0;
            for (Object item : (List<?>) list.getOrDefault("items", List.of())) {
                Map<String, Object> cluster = asMap(item);
                Map<String, Object> metadata = asMap(cluster.get("metadata"));
                Map<String, Object> labels = asMap(metadata.get("labels"));
                if (!"cyfuture-dbaas".equals(labels.get("app.kubernetes.io/managed-by"))) continue;
                // A terminating Cluster cannot be safely patched. In particular, a namespace
                // deletion can make a stale cross-namespace list item unwriteable before it
                // disappears from the list response.
                if (metadata.get("deletionTimestamp") != null) continue;
                if (!setPreferInPlace(cluster, null)) continue;
                String namespace = String.valueOf(metadata.get("namespace"));
                String name = String.valueOf(metadata.get("name"));
                if (namespace.isBlank() || name.isBlank() || "null".equals(namespace)
                        || "null".equals(name)) continue;
                try {
                    customObjectsApi.replaceNamespacedCustomObject(
                            GROUP, VERSION, namespace, CLUSTERS, name, cluster).execute();
                    migrated++;
                } catch (io.kubernetes.client.openapi.ApiException exception) {
                    // The namespace or Cluster can disappear between the global list and the
                    // individual replacement. It is already gone, so there is nothing to migrate.
                    if (exception.getCode() != 404) throw exception;
                }
            }
            return migrated;
        } catch (io.kubernetes.client.openapi.ApiException exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Could not migrate managed Cluster update policies: " + kubernetesMessage(exception));
        }
    }

    /**
     * Checks ready component Pods after a successful VerticalScaling OpsRequest.
     * A successful CR phase alone is not sufficient: the actual container
     * requests and limits must match the user request on every expected Pod.
     */
    public VerticalScalingObservation observeVerticalScaling(String namespace, String databaseId,
                                                              String componentName,
                                                              Map<String, String> requests,
                                                              Map<String, String> limits) {
        ClusterComponentInfo component = requireComponent(namespace, databaseId, componentName);
        int expected = component.replicas() * Math.max(1, component.shards());
        try {
            List<V1Pod> pods = coreV1Api.listNamespacedPod(namespace)
                    .labelSelector("app.kubernetes.io/instance=" + databaseId)
                    .execute().getItems();
            List<V1Pod> componentPods = pods.stream()
                    .filter(pod -> isComponentPod(pod, component.name(), component.sharding()))
                    .toList();
            int matching = 0;
            for (V1Pod pod : componentPods) {
                if (isReady(pod) && resourcesMatch(pod, requests, limits)) matching++;
            }
            boolean complete = componentPods.size() >= expected && matching >= expected;
            String message = complete
                    ? "Requested CPU/memory observed on " + matching + "/" + expected + " ready Pods"
                    : "Waiting for requested CPU/memory on " + matching + "/" + expected
                    + " ready Pods (" + componentPods.size() + " component Pods observed)";
            return new VerticalScalingObservation(complete, expected, componentPods.size(), matching, message);
        } catch (io.kubernetes.client.openapi.ApiException exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Could not verify vertical scaling Pod resources: " + kubernetesMessage(exception));
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

    /**
     * Resolves the policy generated by KubeBlocks for one Cluster. This client
     * deliberately observes policies and the shared BackupRepo only; it never
     * creates, patches, rotates, or otherwise manages either resource.
     */
    public BackupPolicyInfo resolveReadyBackupPolicy(String namespace, String databaseId,
                                                     DatabaseEngine engine,
                                                     String expectedMethod,
                                                     String expectedRepository) {
        return resolveReadyBackupPolicy(namespace, databaseId, engine, expectedMethod, null,
                expectedRepository);
    }

    /**
     * Resolves the policy generated for a Cluster and verifies every requested
     * full/continuous method against both the generated policy and its installed
     * BackupPolicyTemplate. Continuous validation is deliberately opt-in so
     * ordinary full backups retain their existing behavior.
     */
    public BackupPolicyInfo resolveReadyBackupPolicy(String namespace, String databaseId,
                                                     DatabaseEngine engine,
                                                     String expectedMethod,
                                                     String expectedContinuousMethod,
                                                     String expectedRepository) {
        BackupRepositoryInfo repository = requireReadyBackupRepository(expectedRepository);
        if (expectedContinuousMethod != null && !expectedContinuousMethod.isBlank()) {
            validatePitrTemplate(engine, expectedMethod, expectedContinuousMethod);
        }
        try {
            Map<String, Object> list = asMap(customObjectsApi.listNamespacedCustomObject(
                    DATA_PROTECTION_GROUP, DATA_PROTECTION_VERSION, namespace,
                    BACKUP_POLICIES).execute());
            List<Map<String, Object>> policies = new ArrayList<>();
            List<Map<String, Object>> defaults = new ArrayList<>();
            for (Object item : (List<?>) list.getOrDefault("items", List.of())) {
                Map<String, Object> policy = asMap(item);
                Map<String, Object> metadata = asMap(policy.get("metadata"));
                Map<String, Object> spec = asMap(policy.get("spec"));
                if (!belongsToCluster(metadata, spec, databaseId)) continue;
                policies.add(policy);
                Map<String, Object> annotations = asMap(metadata.get("annotations"));
                if (isDefaultBackupPolicy(annotations)) {
                    defaults.add(policy);
                }
            }
            if (policies.isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, "BACKUP_POLICY_NOT_READY", true,
                        "A KubeBlocks BackupPolicy has not been generated for this database yet.");
            }
            if (defaults.size() > 1) {
                throw new ApiException(HttpStatus.CONFLICT, "BACKUP_POLICY_AMBIGUOUS", false,
                        "KubeBlocks reported more than one default BackupPolicy for this database.");
            }
            Map<String, Object> policy = defaults.isEmpty()
                    ? policies.size() == 1 ? policies.get(0) : null
                    : defaults.get(0);
            if (policy == null) {
                throw new ApiException(HttpStatus.CONFLICT, "BACKUP_POLICY_AMBIGUOUS", false,
                        "KubeBlocks did not identify a default BackupPolicy for this database.");
            }

            Map<String, Object> metadata = asMap(policy.get("metadata"));
            Map<String, Object> spec = asMap(policy.get("spec"));
            String policyName = String.valueOf(metadata.get("name"));
            String configuredRepository = optionalText(spec.get("backupRepoName"));
            if (configuredRepository != null && !expectedRepository.equals(configuredRepository)) {
                throw new ApiException(HttpStatus.CONFLICT, "BACKUP_REPOSITORY_MISMATCH", false,
                        "The database BackupPolicy is not configured for the approved BackupRepo.");
            }
            // backupRepoName is optional in KubeBlocks 1.0. An omitted value
            // intentionally selects the cluster's default BackupRepo, so it is
            // valid only when the DBaaS-approved repository is that default.
            if (configuredRepository == null && !repository.defaultRepository()) {
                throw new ApiException(HttpStatus.CONFLICT, "BACKUP_REPOSITORY_MISMATCH", false,
                        "The database BackupPolicy uses the default BackupRepo, which is not the approved BackupRepo.");
            }
            List<String> methodNames = ((List<?>) spec.getOrDefault("backupMethods", List.of())).stream()
                    .map(this::asMap)
                    .map(method -> optionalText(method.get("name")))
                    .filter(Objects::nonNull)
                    .toList();
            boolean methodFound = methodNames.contains(expectedMethod);
            if (!methodFound) {
                throw new ApiException(HttpStatus.CONFLICT, "BACKUP_METHOD_UNSUPPORTED", false,
                        "The configured full backup method is not available in the database BackupPolicy.");
            }
            if (expectedContinuousMethod != null && !expectedContinuousMethod.isBlank()
                    && !methodNames.contains(expectedContinuousMethod)) {
                // The installed template was already proven to support this
                // pair. A generated policy without it is still converging.
                throw new ApiException(HttpStatus.CONFLICT, "BACKUP_POLICY_NOT_READY", true,
                        "The generated KubeBlocks BackupPolicy has not exposed the continuous backup method yet.");
            }
            String observedStatus = policyObservedStatus(asMap(policy.get("status")));
            boolean encryptionConfigured = !asMap(spec.get("encryptionConfig")).isEmpty();
            validateInstalledTemplate(policy, expectedMethod, expectedContinuousMethod);
            return new BackupPolicyInfo(policyName, expectedRepository, expectedMethod,
                    expectedContinuousMethod, encryptionConfigured, observedStatus, engine);
        } catch (io.kubernetes.client.openapi.ApiException exception) {
            throw backupApiFailure("read the generated BackupPolicy", exception);
        }
    }

    /**
     * Updates only Cluster.spec.backup. The shared BackupRepo is read and
     * validated but never created, changed, or removed by this client.
     */
    public void configureScheduledBackup(String namespace, String project, String databaseId,
                                        String method, String repositoryName,
                                        String retentionPeriod, String cronExpression,
                                        boolean enabled) {
        configureScheduledBackup(namespace, project, databaseId, method, null, repositoryName,
                retentionPeriod, cronExpression, enabled, false);
    }

    public void configureScheduledBackup(String namespace, String project, String databaseId,
                                        String method, String continuousMethod, String repositoryName,
                                        String retentionPeriod, String cronExpression,
                                        boolean enabled, boolean pitrEnabled) {
        requireReadyBackupRepository(repositoryName);
        ensureClusterBackupSchemaSupports();
        if (pitrEnabled && (continuousMethod == null || continuousMethod.isBlank())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PITR_NOT_SUPPORTED", false,
                    "The selected database engine has no installed continuous backup method.");
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                Map<String, Object> cluster = new LinkedHashMap<>(asMap(
                        customObjectsApi.getNamespacedCustomObject(
                                GROUP, VERSION, namespace, CLUSTERS, databaseId).execute()));
                Map<String, Object> metadata = asMap(cluster.get("metadata"));
                Map<String, Object> labels = asMap(metadata.get("labels"));
                if (!"cyfuture-dbaas".equals(String.valueOf(labels.get(MANAGED_BY_LABEL)))
                        || !project.equals(String.valueOf(labels.get(PROJECT_LABEL)))
                        || !databaseId.equals(String.valueOf(labels.get(DATABASE_LABEL)))) {
                    throw new ApiException(HttpStatus.CONFLICT, "DATABASE_RESOURCE_NOT_MANAGED", false,
                            "The KubeBlocks Cluster is not owned by this DBaaS database.");
                }
                Map<String, Object> currentBackup = asMap(asMap(cluster.get("spec")).get("backup"));
                // Send a merge patch containing only fields owned by this
                // version. This avoids rewriting unrelated Cluster fields or
                // future KubeBlocks backup fields during every reconciliation.
                Map<String, Object> backup = new LinkedHashMap<>();
                backup.put("enabled", enabled);
                backup.put("method", method);
                // A null is an intentional merge-patch removal when PITR is
                // disabled; existing recovery points are never touched.
                backup.put("continuousMethod", pitrEnabled ? continuousMethod : null);
                backup.put("repoName", repositoryName);
                backup.put("retentionPeriod", retentionPeriod);
                if (cronExpression != null && !cronExpression.isBlank()) {
                    backup.put("cronExpression", cronExpression);
                } else {
                    // JSON merge-patch null removes a previously configured
                    // schedule when automatic backups are disabled.
                    backup.put("cronExpression", null);
                }
                backup.put("pitrEnabled", pitrEnabled);
                backup.put("incrementalBackupEnabled", false);
                // A reconciler may revisit a waiting KubeBlocks policy often.
                // Do not generate needless Cluster writes once desired state is
                // already present.
                if (backupSettingsMatch(currentBackup, backup)) return;
                Map<String, Object> patch = Map.of("spec", Map.of("backup", backup));
                customObjectsApi.patchNamespacedCustomObject(
                                GROUP, VERSION, namespace, CLUSTERS, databaseId, patch)
                        .fieldManager("cyfuture-dbaas")
                        .execute();
                return;
            } catch (io.kubernetes.client.openapi.ApiException exception) {
                if (exception.getCode() == 409 && attempt < 2) continue;
                throw backupApiFailure("configure the Cluster backup policy", exception);
            }
        }
    }

    private boolean backupSettingsMatch(Map<String, Object> current, Map<String, Object> desired) {
        for (Map.Entry<String, Object> setting : desired.entrySet()) {
            Object expected = setting.getValue();
            if (expected == null) {
                if (current.containsKey(setting.getKey()) && current.get(setting.getKey()) != null) return false;
            } else if (!Objects.equals(expected, current.get(setting.getKey()))) {
                return false;
            }
        }
        return true;
    }

    /** Reads the generated BackupSchedule without creating or mutating it. */
    public BackupScheduleInfo observeGeneratedBackupSchedule(String namespace, String databaseId,
                                                             String policyName) {
        try {
            Map<String, Object> list = asMap(customObjectsApi.listNamespacedCustomObject(
                    DATA_PROTECTION_GROUP, DATA_PROTECTION_VERSION, namespace,
                    BACKUP_SCHEDULES).execute());
            List<Map<String, Object>> matches = new ArrayList<>();
            for (Object item : (List<?>) list.getOrDefault("items", List.of())) {
                Map<String, Object> schedule = asMap(item);
                Map<String, Object> metadata = asMap(schedule.get("metadata"));
                Map<String, Object> labels = asMap(metadata.get("labels"));
                Map<String, Object> spec = asMap(schedule.get("spec"));
                String configuredPolicy = optionalText(spec.get("backupPolicyName"));
                String instance = optionalText(labels.get(APP_INSTANCE_LABEL));
                if ((instance == null || databaseId.equals(instance))
                        && (policyName.equals(configuredPolicy) || configuredPolicy == null)) {
                    matches.add(schedule);
                }
            }
            if (matches.size() != 1) {
                return BackupScheduleInfo.missing();
            }
            Map<String, Object> schedule = matches.get(0);
            Map<String, Object> metadata = asMap(schedule.get("metadata"));
            Map<String, Object> status = asMap(schedule.get("status"));
            return new BackupScheduleInfo(true, String.valueOf(metadata.get("name")),
                    policyObservedStatus(status), ready(status));
        } catch (io.kubernetes.client.openapi.ApiException exception) {
            throw backupApiFailure("read the generated BackupSchedule", exception);
        }
    }

    /**
     * Returns only KubeBlocks Backup CRs proven to be owned by a generated
     * BackupSchedule for the requested Cluster. These are candidates for safe
     * metadata import; arbitrary Backup CRs are deliberately excluded.
     */
    public List<ScheduledBackupInfo> listScheduledBackups(String namespace, String databaseId,
                                                           String policyName) {
        try {
            Map<String, Object> list = asMap(customObjectsApi.listNamespacedCustomObject(
                    DATA_PROTECTION_GROUP, DATA_PROTECTION_VERSION, namespace, BACKUPS).execute());
            List<ScheduledBackupInfo> discovered = new ArrayList<>();
            for (Object item : (List<?>) list.getOrDefault("items", List.of())) {
                Map<String, Object> backup = asMap(item);
                Map<String, Object> metadata = asMap(backup.get("metadata"));
                Map<String, Object> labels = asMap(metadata.get("labels"));
                Map<String, Object> spec = asMap(backup.get("spec"));
                String instance = optionalText(labels.get(APP_INSTANCE_LABEL));
                if (!((instance == null || databaseId.equals(instance)))
                        || !policyName.equals(String.valueOf(spec.get("backupPolicyName")))
                        || !hasOwnerKind(metadata, "BackupSchedule")) {
                    continue;
                }
                Map<String, Object> status = asMap(backup.get("status"));
                String name = optionalText(metadata.get("name"));
                String uid = optionalText(metadata.get("uid"));
                if (name == null || uid == null) continue;
                discovered.add(new ScheduledBackupInfo(name, uid,
                        optionalText(spec.get("backupMethod")),
                        optionalText(spec.get("retentionPeriod")),
                        String.valueOf(status.getOrDefault("phase", "New")),
                        safeKubernetesMessage(firstNonBlank(
                                String.valueOf(status.getOrDefault("failureReason", "")),
                                latestConditionMessage(status), "KubeBlocks is processing the backup")),
                        size(status.get("totalSize")), instant(status.get("startTimestamp")),
                        instant(status.get("completionTimestamp"))));
            }
            return discovered;
        } catch (io.kubernetes.client.openapi.ApiException exception) {
            throw backupApiFailure("discover scheduled Backup resources", exception);
        }
    }

    /**
     * Reads generated full and continuous Backup resources from the installed
     * v1alpha1 schema. Import is limited to the database's generated policy
     * and controller-owned children; DBaaS never invents coverage timestamps.
     */
    public List<GeneratedBackupInfo> listGeneratedBackups(String namespace, String databaseId,
                                                           String policyName, String fullMethod,
                                                           String continuousMethod) {
        try {
            Map<String, Object> list = asMap(customObjectsApi.listNamespacedCustomObject(
                    DATA_PROTECTION_GROUP, DATA_PROTECTION_VERSION, namespace, BACKUPS).execute());
            List<GeneratedBackupInfo> discovered = new ArrayList<>();
            for (Object item : (List<?>) list.getOrDefault("items", List.of())) {
                Map<String, Object> backup = asMap(item);
                Map<String, Object> metadata = asMap(backup.get("metadata"));
                Map<String, Object> labels = asMap(metadata.get("labels"));
                Map<String, Object> spec = asMap(backup.get("spec"));
                String instance = optionalText(labels.get(APP_INSTANCE_LABEL));
                String method = optionalText(spec.get("backupMethod"));
                if (!policyName.equals(optionalText(spec.get("backupPolicyName")))
                        || (instance != null && !databaseId.equals(instance))) {
                    continue;
                }
                GeneratedBackupKind kind;
                if (fullMethod.equals(method)) {
                    kind = GeneratedBackupKind.FULL;
                } else if (continuousMethod != null && continuousMethod.equals(method)) {
                    kind = GeneratedBackupKind.CONTINUOUS;
                } else {
                    continue;
                }
                // Manual Backup CRs have DBaaS ownership labels. Generated
                // BackupSchedule/BackupPolicy children are the only automatic
                // resources accepted into history.
                if (!hasOwnerKind(metadata, "BackupSchedule")
                        && !hasOwner(metadata, "BackupPolicy", policyName)) {
                    continue;
                }
                Map<String, Object> status = asMap(backup.get("status"));
                Map<String, Object> timeRange = asMap(status.get("timeRange"));
                String name = optionalText(metadata.get("name"));
                String uid = optionalText(metadata.get("uid"));
                if (name == null || uid == null) continue;
                String parentName = optionalText(status.get("parentBackupName"));
                if (parentName == null) parentName = optionalText(spec.get("parentBackupName"));
                discovered.add(new GeneratedBackupInfo(name, uid, kind, method,
                        optionalText(spec.get("retentionPeriod")),
                        optionalText(status.get("baseBackupName")), parentName,
                        String.valueOf(status.getOrDefault("phase", "New")),
                        safeKubernetesMessage(firstNonBlank(
                                String.valueOf(status.getOrDefault("failureReason", "")),
                                latestConditionMessage(status), "KubeBlocks is processing the backup")),
                        size(status.get("totalSize")), instant(status.get("startTimestamp")),
                        instant(status.get("completionTimestamp")), instant(timeRange.get("start")),
                        instant(timeRange.get("end"))));
            }
            return discovered;
        } catch (io.kubernetes.client.openapi.ApiException exception) {
            throw backupApiFailure("discover generated Backup resources", exception);
        }
    }

    /** Performs the read-only readiness validation required before accepting backup work. */
    public void validateReadyBackupRepository(String repositoryName) {
        requireReadyBackupRepository(repositoryName);
    }

    /**
     * Read-only runtime validation for the exact full/continuous pair selected
     * by an engine strategy. This intentionally lists only template metadata
     * and backup method names; it never reads repository credentials.
     */
    public void validatePitrTemplate(DatabaseEngine engine, String fullMethod,
                                     String continuousMethod) {
        try {
            Map<String, Object> response = asMap(customObjectsApi.listClusterCustomObject(
                    DATA_PROTECTION_GROUP, DATA_PROTECTION_VERSION, BACKUP_POLICY_TEMPLATES).execute());
            boolean pairInstalled = false;
            boolean pairReady = false;
            for (Object item : (List<?>) response.getOrDefault("items", List.of())) {
                Map<String, Object> template = asMap(item);
                List<String> methods = ((List<?>) asMap(template.get("spec"))
                        .getOrDefault("backupMethods", List.of())).stream()
                        .map(this::asMap)
                        .map(method -> optionalText(method.get("name")))
                        .filter(Objects::nonNull)
                        .toList();
                if (!methods.contains(fullMethod) || !methods.contains(continuousMethod)) continue;
                pairInstalled = true;
                if (ready(asMap(template.get("status")))) pairReady = true;
            }
            if (!pairInstalled) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PITR_NOT_SUPPORTED", false,
                        "The installed KubeBlocks BackupPolicyTemplates do not support PITR for "
                                + engine + ".");
            }
            if (!pairReady) {
                throw new ApiException(HttpStatus.CONFLICT, "BACKUP_POLICY_NOT_READY", true,
                        "The installed KubeBlocks BackupPolicyTemplate is not available yet.");
            }
        } catch (io.kubernetes.client.openapi.ApiException exception) {
            if (exception.getCode() == 404) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PITR_NOT_SUPPORTED", false,
                        "KubeBlocks does not expose a BackupPolicyTemplate for point-in-time recovery.");
            }
            throw backupApiFailure("validate the BackupPolicyTemplate", exception);
        }
    }

    /** Lists safe public BackupRepo state only; no Secret references or values leave this client. */
    public List<BackupRepositoryResponse> listBackupRepositories() {
        try {
            Map<String, Object> list = asMap(customObjectsApi.listClusterCustomObject(
                    DATA_PROTECTION_GROUP, DATA_PROTECTION_VERSION, BACKUP_REPOS).execute());
            List<BackupRepositoryResponse> result = new ArrayList<>();
            for (Object item : (List<?>) list.getOrDefault("items", List.of())) {
                Map<String, Object> repository = asMap(item);
                Map<String, Object> metadata = asMap(repository.get("metadata"));
                Map<String, Object> spec = asMap(repository.get("spec"));
                Map<String, Object> status = asMap(repository.get("status"));
                String name = optionalText(metadata.get("name"));
                if (name == null) continue;
                Map<String, Object> provider = asMap(spec.get("storageProviderRef"));
                String providerName = firstNonBlank(optionalText(provider.get("name")),
                        optionalText(spec.get("storageProviderRef")), optionalText(spec.get("storageProvider")),
                        optionalText(status.get("storageProvider")), "unknown");
                result.add(new BackupRepositoryResponse(name, providerName,
                        "true".equalsIgnoreCase(String.valueOf(status.get("isDefault"))), ready(status)));
            }
            return result;
        } catch (io.kubernetes.client.openapi.ApiException exception) {
            throw backupApiFailure("list BackupRepo resources", exception);
        }
    }

    /** Creates a KubeBlocks Backup CR for a policy that was already validated. */
    public void createBackup(String namespace, String project, String databaseId,
                             String backupName, String policyName, String backupMethod,
                             String retentionPeriod, String parentBackupName) {
        createBackup(namespace, project, databaseId, backupName, policyName, backupMethod,
                retentionPeriod, parentBackupName, backupName, null);
    }

    /** Creates a labelled manual Backup CR. Retain makes CR deletion distinct from S3 purging. */
    public void createBackup(String namespace, String project, String databaseId,
                             String backupName, String policyName, String backupMethod,
                             String retentionPeriod, String parentBackupName,
                             String backupId, String operationId) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("backupPolicyName", policyName);
        spec.put("backupMethod", backupMethod);
        // Retain means deleting the CR alone never erases data. A purge is an
        // explicit two-step operation that first changes this known CR to Delete.
        spec.put("deletionPolicy", "Retain");
        spec.put("retentionPeriod", retentionPeriod);
        if (parentBackupName != null && !parentBackupName.isBlank()) {
            spec.put("parentBackupName", parentBackupName);
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", backupName);
        metadata.put("namespace", namespace);
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(MANAGED_BY_LABEL, "cyfuture-dbaas");
        labels.put(APP_INSTANCE_LABEL, databaseId);
        labels.put(PROJECT_LABEL, project);
        labels.put(DATABASE_LABEL, databaseId);
        labels.put(BACKUP_ID_LABEL, backupId);
        if (operationId != null && !operationId.isBlank()) labels.put(OPERATION_ID_LABEL, operationId);
        metadata.put("labels", labels);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("apiVersion", DATA_PROTECTION_GROUP + "/" + DATA_PROTECTION_VERSION);
        body.put("kind", "Backup");
        body.put("metadata", metadata);
        body.put("spec", spec);
        try {
            customObjectsApi.createNamespacedCustomObject(
                    DATA_PROTECTION_GROUP, DATA_PROTECTION_VERSION, namespace, BACKUPS, body).execute();
        } catch (io.kubernetes.client.openapi.ApiException exception) {
            if (exception.getCode() == 409) {
                try {
                    Map<String, Object> existing = asMap(customObjectsApi.getNamespacedCustomObject(
                            DATA_PROTECTION_GROUP, DATA_PROTECTION_VERSION, namespace, BACKUPS,
                            backupName).execute());
                    if (ownedBackup(existing, project, databaseId, backupId, operationId, null, null)) return;
                } catch (io.kubernetes.client.openapi.ApiException readException) {
                    throw backupApiFailure("verify an existing Backup resource", readException);
                }
                throw new ApiException(HttpStatus.CONFLICT, "BACKUP_RESOURCE_NOT_MANAGED", false,
                        "A Backup resource with this name is not owned by this DBaaS backup.");
            }
            throw backupApiFailure("create the Backup resource", exception);
        }
    }

    /** Observes only a known DBaaS Backup CR; it never lists/deletes unknown resources. */
    public BackupObservation observeBackup(String namespace, String backupName) {
        try {
            Map<String, Object> backup = asMap(customObjectsApi.getNamespacedCustomObject(
                    DATA_PROTECTION_GROUP, DATA_PROTECTION_VERSION, namespace, BACKUPS, backupName).execute());
            return backupObservation(backup);
        } catch (io.kubernetes.client.openapi.ApiException exception) {
            if (exception.getCode() == 404) return BackupObservation.missing();
            throw backupApiFailure("observe the Backup resource", exception);
        }
    }

    /**
     * Observes a Backup CR only after proving it still matches the durable
     * metadata identity. This protects history and restore eligibility if a
     * Kubernetes name is later reused by another resource.
     */
    public BackupObservation observeManagedBackup(String namespace, String project, String databaseId,
                                                  String backupId, String operationId, String backupName,
                                                  String expectedUid, String expectedPolicyName) {
        try {
            Map<String, Object> backup = asMap(customObjectsApi.getNamespacedCustomObject(
                    DATA_PROTECTION_GROUP, DATA_PROTECTION_VERSION, namespace, BACKUPS, backupName).execute());
            if (!ownedBackup(backup, project, databaseId, backupId, operationId,
                    expectedUid, expectedPolicyName)) {
                throw new ApiException(HttpStatus.CONFLICT, "BACKUP_RESOURCE_NOT_MANAGED", false,
                        "The KubeBlocks Backup resource is not owned by this DBaaS backup.");
            }
            return backupObservation(backup);
        } catch (io.kubernetes.client.openapi.ApiException exception) {
            if (exception.getCode() == 404) return BackupObservation.missing();
            throw backupApiFailure("observe the Backup resource", exception);
        }
    }

    /**
     * Deletes a known DBaaS manual Backup or a previously discovered generated
     * schedule Backup. `purgeData` is the only path that changes deletionPolicy
     * to Delete before removing the CR.
     */
    public void deleteManagedBackup(String namespace, String project, String databaseId,
                                    String backupId, String operationId, String backupName,
                                    String expectedUid, String expectedPolicyName,
                                    boolean purgeData) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                Map<String, Object> backup = asMap(customObjectsApi.getNamespacedCustomObject(
                        DATA_PROTECTION_GROUP, DATA_PROTECTION_VERSION, namespace, BACKUPS, backupName).execute());
                if (!ownedBackup(backup, project, databaseId, backupId, operationId,
                        expectedUid, expectedPolicyName)) {
                    throw new ApiException(HttpStatus.CONFLICT, "BACKUP_RESOURCE_NOT_MANAGED", false,
                            "The KubeBlocks Backup resource is not managed by this DBaaS backup.");
                }
                if (purgeData) {
                    mutableChildMap(backup, "spec").put("deletionPolicy", "Delete");
                    try {
                        customObjectsApi.replaceNamespacedCustomObject(
                                DATA_PROTECTION_GROUP, DATA_PROTECTION_VERSION, namespace, BACKUPS,
                                backupName, backup).execute();
                    } catch (io.kubernetes.client.openapi.ApiException exception) {
                        if (exception.getCode() == 409 && attempt < 2) continue;
                        throw exception;
                    }
                }
                customObjectsApi.deleteNamespacedCustomObject(
                        DATA_PROTECTION_GROUP, DATA_PROTECTION_VERSION, namespace, BACKUPS, backupName).execute();
                return;
            } catch (io.kubernetes.client.openapi.ApiException exception) {
                if (exception.getCode() == 404) return;
                throw backupApiFailure("delete the Backup resource", exception);
            }
        }
    }

    /**
     * Observes the Restore resource created as a child of the known Restore
     * OpsRequest. It is read-only and rejects an ambiguous or unrelated CR,
     * rather than attributing another tenant's restore to this operation.
     */
    public RestoreObservation observeRestore(String namespace, String opsRequestName,
                                             String knownRestoreName) {
        try {
            if (knownRestoreName != null && !knownRestoreName.isBlank()) {
                Map<String, Object> restore = asMap(customObjectsApi.getNamespacedCustomObject(
                        DATA_PROTECTION_GROUP, DATA_PROTECTION_VERSION, namespace,
                        RESTORES, knownRestoreName).execute());
                if (!ownedRestore(restore, opsRequestName)) {
                    throw new ApiException(HttpStatus.CONFLICT, "RESTORE_RESOURCE_NOT_MANAGED", false,
                            "The KubeBlocks Restore resource is not owned by this restore operation.");
                }
                return restoreObservation(restore);
            }
            Map<String, Object> list = asMap(customObjectsApi.listNamespacedCustomObject(
                    DATA_PROTECTION_GROUP, DATA_PROTECTION_VERSION, namespace, RESTORES).execute());
            List<Map<String, Object>> matches = new ArrayList<>();
            for (Object item : (List<?>) list.getOrDefault("items", List.of())) {
                Map<String, Object> restore = asMap(item);
                if (ownedRestore(restore, opsRequestName)) matches.add(restore);
            }
            if (matches.isEmpty()) return RestoreObservation.missing();
            if (matches.size() > 1) {
                throw new ApiException(HttpStatus.CONFLICT, "RESTORE_RESOURCE_AMBIGUOUS", false,
                        "KubeBlocks reported more than one Restore resource for this operation.");
            }
            return restoreObservation(matches.get(0));
        } catch (io.kubernetes.client.openapi.ApiException exception) {
            if (exception.getCode() == 404) return RestoreObservation.missing();
            throw backupApiFailure("observe the KubeBlocks Restore resource", exception);
        }
    }

    /** Mirrors kbcli cluster restore: a Restore OpsRequest creates a new Cluster. */
    public void createRestoreOpsRequest(String namespace, String project, String databaseId,
                                        String operationName, String backupName,
                                        Instant restoreTime) {
        createRestoreOpsRequest(namespace, project, databaseId, operationName, backupName,
                null, null, null, restoreTime);
    }

    /** Mirrors the KubeBlocks Restore OpsRequest schema while retaining source backup identity. */
    public void createRestoreOpsRequest(String namespace, String project, String databaseId,
                                        String operationName, String backupName,
                                        String backupNamespace, String backupId, String operationId,
                                        Instant restoreTime) {
        Map<String, Object> restore = new LinkedHashMap<>();
        restore.put("backupName", backupName);
        if (backupNamespace != null && !backupNamespace.isBlank() && !namespace.equals(backupNamespace)) {
            restore.put("backupNamespace", backupNamespace);
        }
        restore.put("volumeRestorePolicy", "Parallel");
        if (restoreTime != null) restore.put("restorePointInTime", restoreTime.toString());
        ensureOpsRequestSchemaSupports("restore");
        ensureRestoreSchemaSupports(restoreTime != null);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", operationName);
        metadata.put("namespace", namespace);
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(MANAGED_BY_LABEL, "cyfuture-dbaas");
        labels.put(PROJECT_LABEL, project);
        labels.put(DATABASE_LABEL, databaseId);
        labels.put("dbaas.cyfuture.com/restore", "true");
        if (backupId != null && !backupId.isBlank()) labels.put(BACKUP_ID_LABEL, backupId);
        if (operationId != null && !operationId.isBlank()) labels.put(OPERATION_ID_LABEL, operationId);
        metadata.put("labels", labels);
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("clusterName", databaseId);
        spec.put("type", "Restore");
        spec.put("force", false);
        spec.put("restore", restore);
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
            throw backupApiFailure("create the Restore OpsRequest", exception);
        }
    }

    public OpsRequestInfo getOpsRequest(String namespace, String opsRequestName) {
        try {
            Map<String, Object> object = asMap(customObjectsApi.getNamespacedCustomObject(
                    OPS_GROUP, OPS_VERSION, namespace, OPS_REQUESTS, opsRequestName).execute());
            Map<String, Object> status = asMap(object.get("status"));
            return new OpsRequestInfo(String.valueOf(status.getOrDefault("phase", "Pending")),
                    String.valueOf(status.getOrDefault("progress", "-/-")),
                    lastConditionMessage(status),
                    lastConditionReason(status),
                    instant(status.get("startTimestamp")),
                    instant(status.get("completionTimestamp")));
        } catch (io.kubernetes.client.openapi.ApiException exception) {
            if (exception.getCode() == 404) {
                return new OpsRequestInfo("Pending", "-/-",
                        "Waiting for KubeBlocks OpsRequest submission", null, null, null);
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

//        Map<String, Object> resources = Map.of(
//                "requests", Map.of("cpu", request.size().cpu(), "memory", request.size().memory()),
//                "limits", Map.of("cpu", request.size().cpu(), "memory", request.size().memory()));


        Map<String, Object> resources = Map.of(
                "requests", Map.of(
                        "cpu", request.size().getCpuRequest(),
                        "memory", request.size().getMemoryRequest()
                ),
                "limits", Map.of(
                        "cpu", request.size().getCpuLimit(),
                        "memory", request.size().getMemoryLimit()
                )
        );


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
        component.put("podUpdatePolicy", PREFER_IN_PLACE);
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
        if (request.backup() != null) {
            ensureClusterBackupSchemaSupports();
            int retentionDays = request.backup().retentionDays() == null
                    ? 7 : request.backup().retentionDays();
            boolean pitrEnabled = Boolean.TRUE.equals(request.backup().pitrEnabled());
            Map<String, Object> backup = new LinkedHashMap<>();
            backup.put("enabled", Boolean.TRUE.equals(request.backup().autoBackupEnabled()));
            backup.put("method", backupMethod(request.engine()));
            backup.put("continuousMethod", pitrEnabled ? continuousBackupMethod(request.engine()) : null);
            backup.put("repoName", request.backup().repository() == null || request.backup().repository().isBlank()
                    ? properties.getBackup().getRepositoryName() : request.backup().repository());
            backup.put("retentionPeriod", retentionDays + "d");
            if (request.backup().cronExpression() != null && !request.backup().cronExpression().isBlank()) {
                backup.put("cronExpression", request.backup().cronExpression());
            }
            backup.put("pitrEnabled", pitrEnabled);
            backup.put("incrementalBackupEnabled", false);
            spec.put("backup", backup);
        }

        if (request.mode() == DatabaseMode.SHARDING) {
            Map<String, Object> shard = new LinkedHashMap<>();
            shard.put("name", "shard");
            shard.put("serviceVersion", request.version());
            shard.put("replicas", request.replicas());
            shard.put("podUpdatePolicy", PREFER_IN_PLACE);
            shard.put("resources", resources);
            shard.put("volumeClaimTemplates", List.of(volume));

            Map<String, Object> configServer = new LinkedHashMap<>();
            configServer.put("name", "config-server");
            configServer.put("serviceVersion", request.version());
            configServer.put("replicas", 3);
            configServer.put("podUpdatePolicy", PREFER_IN_PLACE);
            configServer.put("resources", resources);
            configServer.put("volumeClaimTemplates", List.of(volume));

            Map<String, Object> mongos = new LinkedHashMap<>();
            mongos.put("name", "mongos");
            mongos.put("serviceVersion", request.version());
            mongos.put("replicas", 2);
            mongos.put("podUpdatePolicy", PREFER_IN_PLACE);
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

    private String backupMethod(DatabaseEngine engine) {
        return switch (engine) {
            case POSTGRESQL -> "pg-basebackup";
            case MYSQL -> "xtrabackup";
            case MONGODB -> "dump";
        };
    }

    private String continuousBackupMethod(DatabaseEngine engine) {
        return switch (engine) {
            case POSTGRESQL -> "archive-wal";
            case MYSQL -> "archive-binlog";
            case MONGODB -> "archive-oplog";
        };
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

    private boolean setPreferInPlace(Map<String, Object> cluster, String componentName) {
        Map<String, Object> spec = asMap(cluster.get("spec"));
        boolean changed = false;
        for (Object item : (List<?>) spec.getOrDefault("componentSpecs", List.of())) {
            Map<String, Object> component = asMap(item);
            if ((componentName == null || componentName.equals(component.get("name")))
                    && !PREFER_IN_PLACE.equals(component.get("podUpdatePolicy"))) {
                component.put("podUpdatePolicy", PREFER_IN_PLACE);
                changed = true;
            }
        }
        for (Object item : (List<?>) spec.getOrDefault("shardings", List.of())) {
            Map<String, Object> template = asMap(asMap(item).get("template"));
            if ((componentName == null || componentName.equals(template.get("name")))
                    && !PREFER_IN_PLACE.equals(template.get("podUpdatePolicy"))) {
                template.put("podUpdatePolicy", PREFER_IN_PLACE);
                changed = true;
            }
        }
        return changed;
    }

    private List<String> componentNames(List<ClusterComponentInfo> components) {
        return components.stream().map(ClusterComponentInfo::name).toList();
    }

    private void createOpsRequest(String namespace, String operationId, String type,
                                  String operationField, Object operationPayload,
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

    /** Verifies the exact installed Cluster v1 backup schema before any patch/create uses it. */
    private void ensureClusterBackupSchemaSupports() {
        synchronized (this) {
            if (clusterBackupSchemaVerified) return;
        }
        try {
            Map<String, Object> crd = asMap(customObjectsApi.getClusterCustomObject(
                    "apiextensions.k8s.io", "v1", "customresourcedefinitions", CLUSTER_CRD).execute());
            Map<String, Object> properties = crdSpecProperties(crd, VERSION);
            Map<String, Object> backup = asMap(asMap(properties.get("backup")).get("properties"));
            List<String> required = List.of("enabled", "method", "continuousMethod", "repoName",
                    "retentionPeriod", "cronExpression", "pitrEnabled", "incrementalBackupEnabled");
            if (!backup.keySet().containsAll(required)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PITR_NOT_SUPPORTED", false,
                        "The installed KubeBlocks Cluster CRD does not expose the required backup fields.");
            }
            synchronized (this) {
                clusterBackupSchemaVerified = true;
            }
        } catch (io.kubernetes.client.openapi.ApiException exception) {
            throw backupApiFailure("inspect the Cluster backup CRD", exception);
        }
    }

    /** Ensures the installed Restore OpsRequest supports PITR's restorePointInTime field. */
    private void ensureRestoreSchemaSupports(boolean pointInTime) {
        String cacheKey = pointInTime ? "point-in-time" : "full";
        synchronized (verifiedRestoreFields) {
            if (verifiedRestoreFields.contains(cacheKey)) return;
        }
        try {
            Map<String, Object> crd = asMap(customObjectsApi.getClusterCustomObject(
                    "apiextensions.k8s.io", "v1", "customresourcedefinitions", OPS_REQUEST_CRD).execute());
            Map<String, Object> restore = asMap(asMap(opsRequestSpecProperties(crd).get("restore"))
                    .get("properties"));
            if (!restore.containsKey("backupName") || !restore.containsKey("volumeRestorePolicy")
                    || (pointInTime && !restore.containsKey("restorePointInTime"))) {
                String code = pointInTime ? "PITR_NOT_SUPPORTED" : "BACKUP_POLICY_NOT_READY";
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, false,
                        "The installed KubeBlocks Restore OpsRequest schema does not support this restore mode.");
            }
            synchronized (verifiedRestoreFields) {
                verifiedRestoreFields.add(cacheKey);
            }
        } catch (io.kubernetes.client.openapi.ApiException exception) {
            throw backupApiFailure("inspect the Restore OpsRequest CRD", exception);
        }
    }

    private Map<String, Object> crdSpecProperties(Map<String, Object> crd, String apiVersion) {
        List<?> versions = (List<?>) asMap(crd.get("spec")).getOrDefault("versions", List.of());
        for (Object item : versions) {
            Map<String, Object> version = asMap(item);
            if (!apiVersion.equals(version.get("name"))) continue;
            Map<String, Object> schema = asMap(version.get("schema"));
            Map<String, Object> openApi = asMap(schema.get("openAPIV3Schema"));
            Map<String, Object> properties = asMap(openApi.get("properties"));
            return asMap(asMap(properties.get("spec")).get("properties"));
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

    private String lastConditionReason(Map<String, Object> status) {
        List<?> conditions = (List<?>) status.getOrDefault("conditions", List.of());
        if (conditions.isEmpty()) return null;
        String latest = null;
        for (Object item : conditions) {
            Map<String, Object> condition = asMap(item);
            Object reason = condition.get("reason");
            String message = String.valueOf(condition.getOrDefault("message", ""));
            if (message.contains("InstanceUpdateRestricted")) return "InstanceUpdateRestricted";
            if (reason == null) continue;
            latest = String.valueOf(reason);
            // Preserve this terminal/restriction reason even if KubeBlocks adds
            // a later generic progress condition.
            if ("InstanceUpdateRestricted".equalsIgnoreCase(latest)) return latest;
        }
        return latest;
    }

    private Instant instant(Object value) {
        if (value == null) return null;
        try {
            return Instant.parse(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    private DatabaseObservation toObservation(String namespace, Map<String, Object> cluster) {
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
        if (databaseStatus == DatabaseStatus.RUNNING
                && (readyReplicas < expectedPods || readyVolumes < expectedVolumes
                || !serviceReady)) {
            databaseStatus = DatabaseStatus.PROVISIONING;
        }

        return new DatabaseObservation(
                id,
                String.valueOf(annotations.getOrDefault("dbaas.cyfuture.com/display-name", id)),
                engine,
                mode,
                String.valueOf(annotations.get("dbaas.cyfuture.com/version")),
                SizePlan.valueOf(String.valueOf(annotations.getOrDefault("dbaas.cyfuture.com/size", "C1G1"))),
                storageGiFromComponent(component, annotations),
                Boolean.parseBoolean(String.valueOf(annotations.getOrDefault(
                        "dbaas.cyfuture.com/deletion-protection", "false"))),
                databaseStatus,
                replicas,
                readyReplicas,
                readyVolumes,
                serviceReady,
                privateHost,
                port,
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

    private boolean isComponentPod(V1Pod pod, String componentName, boolean sharding) {
        if (pod.getMetadata() == null || pod.getMetadata().getLabels() == null) return false;
        Map<String, String> labels = pod.getMetadata().getLabels();
        String actual = firstLabel(labels,
                "apps.kubeblocks.io/component-name",
                "app.kubernetes.io/component",
                "kubeblocks.io/component-name");
        // Single-component non-sharded clusters sometimes omit a component label.
        return componentName.equals(actual) || (!sharding && actual == null);
    }

    private String firstLabel(Map<String, String> labels, String... keys) {
        for (String key : keys) {
            String value = labels.get(key);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private boolean resourcesMatch(V1Pod pod, Map<String, String> requests,
                                   Map<String, String> limits) {
        if (pod.getSpec() == null || pod.getSpec().getContainers() == null) return false;
        return pod.getSpec().getContainers().stream()
                .filter(container -> container.getResources() != null)
                .anyMatch(container -> quantityMapMatches(container.getResources().getRequests(), requests)
                        && quantityMapMatches(container.getResources().getLimits(), limits));
    }

    private boolean quantityMapMatches(Map<String, ?> actual, Map<String, String> requested) {
        if (actual == null) return false;
        return quantityEquals(actual.get("cpu"), requested.get("cpu"), true)
                && quantityEquals(actual.get("memory"), requested.get("memory"), false);
    }

    private boolean quantityEquals(Object actual, String requested, boolean cpu) {
        if (actual == null || requested == null) return false;
        String actualValue = actual instanceof Quantity quantity
                ? quantity.toSuffixedString() : String.valueOf(actual);
        try {
            return cpu ? cpuMillis(actualValue) == cpuMillis(requested)
                    : memoryBytes(actualValue) == memoryBytes(requested);
        } catch (IllegalArgumentException ignored) {
            return actualValue.equals(requested);
        }
    }

    private long cpuMillis(String value) {
        String normalized = value.trim();
        if (normalized.endsWith("m")) return Long.parseLong(normalized.substring(0, normalized.length() - 1));
        return Math.round(Double.parseDouble(normalized) * 1000D);
    }

    private long memoryBytes(String value) {
        String normalized = value.trim();
        Matcher matcher = Pattern.compile("^([0-9]+)(Ki|Mi|Gi|Ti)$").matcher(normalized);
        if (!matcher.matches()) throw new IllegalArgumentException("Unsupported memory quantity " + value);
        long multiplier = switch (matcher.group(2)) {
            case "Ki" -> 1024L;
            case "Mi" -> 1024L * 1024L;
            case "Gi" -> 1024L * 1024L * 1024L;
            case "Ti" -> 1024L * 1024L * 1024L * 1024L;
            default -> throw new IllegalArgumentException("Unsupported memory quantity " + value);
        };
        return Math.multiplyExact(Long.parseLong(matcher.group(1)), multiplier);
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

    /**
     * The generated Kubernetes Java client sends CustomObject PATCH requests as
     * application/json, which some API servers reject for CRDs. Use a fresh
     * full-resource replace instead, retrying only resource-version conflicts.
     */
    private void updateDeletionProtection(String namespace, String databaseId, boolean enabled)
            throws io.kubernetes.client.openapi.ApiException {
        for (int attempt = 0; attempt < 3; attempt++) {
            Map<String, Object> cluster = new LinkedHashMap<>(asMap(
                    customObjectsApi.getNamespacedCustomObject(
                            GROUP, VERSION, namespace, CLUSTERS, databaseId).execute()));
            mutableChildMap(cluster, "spec").put(
                    "terminationPolicy", enabled ? "DoNotTerminate" : "Delete");
            mutableChildMap(mutableChildMap(cluster, "metadata"), "annotations").put(
                    "dbaas.cyfuture.com/deletion-protection", String.valueOf(enabled));
            try {
                customObjectsApi.replaceNamespacedCustomObject(
                        GROUP, VERSION, namespace, CLUSTERS, databaseId, cluster).execute();
                return;
            } catch (io.kubernetes.client.openapi.ApiException exception) {
                if (exception.getCode() != 409 || attempt == 2) throw exception;
            }
        }
    }

    private Map<String, Object> mutableChildMap(Map<String, Object> parent, String key) {
        Map<String, Object> copy = new LinkedHashMap<>(asMap(parent.get(key)));
        parent.put(key, copy);
        return copy;
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

    private BackupRepositoryInfo requireReadyBackupRepository(String repositoryName) {
        try {
            Map<String, Object> repository = asMap(customObjectsApi.getClusterCustomObject(
                    DATA_PROTECTION_GROUP, DATA_PROTECTION_VERSION, BACKUP_REPOS, repositoryName).execute());
            Map<String, Object> status = asMap(repository.get("status"));
            if (!ready(status)) {
                throw new ApiException(HttpStatus.CONFLICT, "BACKUP_REPOSITORY_NOT_READY", true,
                        "The approved KubeBlocks BackupRepo is not Ready.");
            }
            return new BackupRepositoryInfo("true".equalsIgnoreCase(
                    String.valueOf(status.get("isDefault"))));
        } catch (io.kubernetes.client.openapi.ApiException exception) {
            if (exception.getCode() == 404) {
                throw new ApiException(HttpStatus.CONFLICT, "BACKUP_REPOSITORY_NOT_READY", true,
                        "The approved KubeBlocks BackupRepo is not available.");
            }
            throw backupApiFailure("verify the approved BackupRepo", exception);
        }
    }

    /**
     * The generated policy is the authoritative binding to an installed
     * BackupPolicyTemplate. When KubeBlocks exposes that template link, verify
     * the template itself still advertises the selected method as well.
     */
    private void validateInstalledTemplate(Map<String, Object> policy, String expectedMethod,
                                           String expectedContinuousMethod) {
        Map<String, Object> metadata = asMap(policy.get("metadata"));
        Map<String, Object> annotations = asMap(metadata.get("annotations"));
        String templateName = optionalText(annotations.get("apps.kubeblocks.io/backup-policy-template"));
        if (templateName == null) {
            templateName = optionalText(annotations.get("dataprotection.kubeblocks.io/backup-policy-template"));
        }
        if (templateName == null) {
            for (Object reference : (List<?>) metadata.getOrDefault("ownerReferences", List.of())) {
                Map<String, Object> owner = asMap(reference);
                if ("BackupPolicyTemplate".equals(String.valueOf(owner.get("kind")))) {
                    templateName = optionalText(owner.get("name"));
                    break;
                }
            }
        }
        // Older v1.0 controllers do not persist a direct template name on the
        // generated policy. Its backupMethods were already checked above.
        if (templateName == null) return;
        try {
            Map<String, Object> template = asMap(customObjectsApi.getClusterCustomObject(
                    DATA_PROTECTION_GROUP, DATA_PROTECTION_VERSION,
                    BACKUP_POLICY_TEMPLATES, templateName).execute());
            Map<String, Object> spec = asMap(template.get("spec"));
            if (!ready(asMap(template.get("status")))) {
                throw new ApiException(HttpStatus.CONFLICT, "BACKUP_POLICY_TEMPLATE_NOT_READY", true,
                        "The KubeBlocks BackupPolicyTemplate is not available yet.");
            }
            List<String> methods = ((List<?>) spec.getOrDefault("backupMethods", List.of())).stream()
                    .map(this::asMap)
                    .map(method -> optionalText(method.get("name")))
                    .filter(Objects::nonNull)
                    .toList();
            if (!methods.isEmpty() && !methods.contains(expectedMethod)) {
                throw new ApiException(HttpStatus.CONFLICT, "BACKUP_METHOD_UNSUPPORTED", false,
                        "The installed KubeBlocks BackupPolicyTemplate does not support this backup method.");
            }
            if (expectedContinuousMethod != null && !expectedContinuousMethod.isBlank()
                    && !methods.contains(expectedContinuousMethod)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PITR_NOT_SUPPORTED", false,
                        "The installed KubeBlocks BackupPolicyTemplate does not support the continuous backup method.");
            }
        } catch (io.kubernetes.client.openapi.ApiException exception) {
            if (exception.getCode() == 404) {
                throw new ApiException(HttpStatus.CONFLICT, "BACKUP_POLICY_TEMPLATE_NOT_FOUND", true,
                        "The KubeBlocks BackupPolicyTemplate is not available yet.");
            }
            throw backupApiFailure("validate the BackupPolicyTemplate", exception);
        }
    }

    private boolean hasOwnerKind(Map<String, Object> metadata, String kind) {
        return ((List<?>) metadata.getOrDefault("ownerReferences", List.of())).stream()
                .map(this::asMap)
                .anyMatch(owner -> kind.equals(String.valueOf(owner.get("kind"))));
    }

    private boolean hasOwner(Map<String, Object> metadata, String kind, String name) {
        return ((List<?>) metadata.getOrDefault("ownerReferences", List.of())).stream()
                .map(this::asMap)
                .anyMatch(owner -> kind.equals(String.valueOf(owner.get("kind")))
                && name.equals(String.valueOf(owner.get("name"))));
    }

    /** KubeBlocks versions differ in whether generated children copy the instance label. */
    private boolean belongsToCluster(Map<String, Object> metadata, Map<String, Object> spec,
                                     String databaseId) {
        Map<String, Object> labels = asMap(metadata.get("labels"));
        String instance = optionalText(labels.get(APP_INSTANCE_LABEL));
        return databaseId.equals(instance)
                || databaseId.equals(optionalText(spec.get("clusterName")))
                || hasOwner(metadata, "Cluster", databaseId);
    }

    private boolean ownedRestore(Map<String, Object> restore, String opsRequestName) {
        return hasOwner(asMap(restore.get("metadata")), "OpsRequest", opsRequestName);
    }

    private RestoreObservation restoreObservation(Map<String, Object> restore) {
        Map<String, Object> metadata = asMap(restore.get("metadata"));
        Map<String, Object> status = asMap(restore.get("status"));
        String message = firstNonBlank(String.valueOf(status.getOrDefault("failureReason", "")),
                latestConditionMessage(status), "KubeBlocks is processing the restore");
        return new RestoreObservation(true, optionalText(metadata.get("name")),
                String.valueOf(status.getOrDefault("phase", "Running")), safeKubernetesMessage(message),
                instant(status.get("startTimestamp")), instant(status.get("completionTimestamp")));
    }

    private BackupObservation backupObservation(Map<String, Object> backup) {
        Map<String, Object> status = asMap(backup.get("status"));
        Map<String, Object> metadata = asMap(backup.get("metadata"));
        Map<String, Object> timeRange = asMap(status.get("timeRange"));
        String phase = String.valueOf(status.getOrDefault("phase", "New"));
        String message = firstNonBlank(String.valueOf(status.getOrDefault("failureReason", "")),
                latestConditionMessage(status), "KubeBlocks is processing the backup");
        return new BackupObservation(true, phase, safeKubernetesMessage(message),
                size(status.get("totalSize")), instant(status.get("startTimestamp")),
                instant(status.get("completionTimestamp")), optionalText(metadata.get("uid")),
                instant(status.get("expiration")), optionalText(status.get("parentBackupName")),
                optionalText(status.get("baseBackupName")), instant(timeRange.get("start")),
                instant(timeRange.get("end")));
    }

    private boolean ownedBackup(Map<String, Object> backup, String project, String databaseId,
                                String backupId, String operationId,
                                String expectedUid, String expectedPolicyName) {
        Map<String, Object> metadata = asMap(backup.get("metadata"));
        Map<String, Object> labels = asMap(metadata.get("labels"));
        boolean manual = "cyfuture-dbaas".equals(String.valueOf(labels.get(MANAGED_BY_LABEL)))
                && project.equals(String.valueOf(labels.get(PROJECT_LABEL)))
                && databaseId.equals(String.valueOf(labels.get(DATABASE_LABEL)))
                && backupId.equals(String.valueOf(labels.get(BACKUP_ID_LABEL)))
                && operationId != null && !operationId.isBlank()
                && operationId.equals(String.valueOf(labels.get(OPERATION_ID_LABEL)));
        if (manual) {
            return true;
        }
        // Imported automatic backups are trusted only after discovery proved
        // they are generated by the database's BackupSchedule. Require the
        // originally observed UID and policy name as well, so a later CR with
        // a reused name can never be deleted as if it were the historic one.
        Map<String, Object> spec = asMap(backup.get("spec"));
        String instance = optionalText(labels.get(APP_INSTANCE_LABEL));
        return expectedUid != null && !expectedUid.isBlank()
                && expectedPolicyName != null && !expectedPolicyName.isBlank()
                && expectedUid.equals(optionalText(metadata.get("uid")))
                && expectedPolicyName.equals(optionalText(spec.get("backupPolicyName")))
                && (instance == null || databaseId.equals(instance))
                && (hasOwnerKind(metadata, "BackupSchedule")
                || hasOwner(metadata, "BackupPolicy", expectedPolicyName));
    }

    private boolean isDefaultBackupPolicy(Map<String, Object> annotations) {
        return DEFAULT_BACKUP_POLICY_ANNOTATIONS.stream().anyMatch(annotation ->
                "true".equalsIgnoreCase(String.valueOf(annotations.get(annotation))));
    }

    private String optionalText(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isBlank() || "null".equalsIgnoreCase(text) ? null : text;
    }

    private boolean ready(Map<String, Object> status) {
        if ("Ready".equalsIgnoreCase(String.valueOf(status.get("phase")))
                || "Available".equalsIgnoreCase(String.valueOf(status.get("phase")))) return true;
        return ((List<?>) status.getOrDefault("conditions", List.of())).stream()
                .map(this::asMap)
                .anyMatch(condition -> ("Ready".equalsIgnoreCase(String.valueOf(condition.get("type")))
                        || "Available".equalsIgnoreCase(String.valueOf(condition.get("type"))))
                        && "True".equalsIgnoreCase(String.valueOf(condition.get("status"))));
    }

    private String policyObservedStatus(Map<String, Object> status) {
        if (ready(status)) return "AVAILABLE";
        Object phase = status.get("phase");
        return phase == null || String.valueOf(phase).isBlank() ? "OBSERVED" : String.valueOf(phase);
    }

    private Long size(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null) return null;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            // KubeBlocks reports BackupStatus.totalSize as a Kubernetes
            // quantity (for example 1Gi), not always a raw byte count.
            String text = String.valueOf(value).trim();
            java.util.regex.Matcher matcher = Pattern.compile(
                    "^([0-9]+(?:\\.[0-9]+)?)(Ki|Mi|Gi|Ti|Pi|Ei|K|M|G|T|P|E)?$").matcher(text);
            if (!matcher.matches()) return null;
            try {
                BigDecimal amount = new BigDecimal(matcher.group(1));
                String unit = matcher.group(2);
                BigDecimal multiplier = switch (unit == null ? "" : unit) {
                    case "Ki" -> BigDecimal.valueOf(1024L);
                    case "Mi" -> BigDecimal.valueOf(1024L).pow(2);
                    case "Gi" -> BigDecimal.valueOf(1024L).pow(3);
                    case "Ti" -> BigDecimal.valueOf(1024L).pow(4);
                    case "Pi" -> BigDecimal.valueOf(1024L).pow(5);
                    case "Ei" -> BigDecimal.valueOf(1024L).pow(6);
                    case "K" -> BigDecimal.valueOf(1000L);
                    case "M" -> BigDecimal.valueOf(1000L).pow(2);
                    case "G" -> BigDecimal.valueOf(1000L).pow(3);
                    case "T" -> BigDecimal.valueOf(1000L).pow(4);
                    case "P" -> BigDecimal.valueOf(1000L).pow(5);
                    case "E" -> BigDecimal.valueOf(1000L).pow(6);
                    default -> BigDecimal.ONE;
                };
                return amount.multiply(multiplier).setScale(0, RoundingMode.HALF_UP).longValueExact();
            } catch (ArithmeticException exception) {
                return null;
            }
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank() && !"null".equalsIgnoreCase(value)) return value;
        }
        return "KubeBlocks is processing the backup";
    }

    private ApiException backupApiFailure(String action,
                                          io.kubernetes.client.openapi.ApiException exception) {
        int code = exception.getCode();
        if (code == 401 || code == 403) {
            return new ApiException(HttpStatus.FORBIDDEN, "BACKUP_KUBERNETES_FORBIDDEN", false,
                    "Kubernetes denied the requested backup operation.");
        }
        if (code == 404) {
            return new ApiException(HttpStatus.CONFLICT, "BACKUP_KUBERNETES_RESOURCE_NOT_FOUND", true,
                    "A required KubeBlocks backup resource is not available yet.");
        }
        if (code == 408 || code == 429 || code >= 500 || code == 0) {
            return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "BACKUP_KUBERNETES_UNAVAILABLE", true,
                    "Kubernetes is temporarily unavailable for backup processing.");
        }
        return new ApiException(HttpStatus.BAD_GATEWAY, "BACKUP_KUBERNETES_REJECTED", false,
                "Kubernetes rejected the requested backup operation.");
    }

    private String safeKubernetesMessage(String message) {
        if (message == null || message.isBlank()) return "KubeBlocks is processing the backup";
        String sanitized = message.replaceAll(
                "(?i)(password|passwd|pwd|token|secret|access[_-]?key|credential|passphrase)"
                        + "\\s*[:=]\\s*[^\\s,;\\\"']+", "$1=******");
        if (SENSITIVE_BACKUP_DIAGNOSTIC.matcher(sanitized).find()) {
            return "KubeBlocks reported a backup lifecycle update. Check platform logs.";
        }
        sanitized = sanitized.trim();
        return sanitized.length() <= 500 ? sanitized : sanitized.substring(0, 500);
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
            String reason,
            Instant startedAt,
            Instant completedAt
    ) {
        /** Backward-compatible constructor for callers that do not supply a condition reason. */
        public OpsRequestInfo(String phase, String progress, String message,
                              Instant startedAt, Instant completedAt) {
            this(phase, progress, message, null, startedAt, completedAt);
        }
    }

    public record BackupPolicyInfo(
            String policyName,
            String repositoryName,
            String backupMethod,
            String continuousMethod,
            boolean encryptionConfigured,
            String observedStatus,
            DatabaseEngine engine
    ) {
        /** Backward-compatible constructor for full-backup-only callers. */
        public BackupPolicyInfo(String policyName, String repositoryName, String backupMethod,
                                boolean encryptionConfigured, String observedStatus,
                                DatabaseEngine engine) {
            this(policyName, repositoryName, backupMethod, null, encryptionConfigured, observedStatus, engine);
        }
    }

    private record BackupRepositoryInfo(boolean defaultRepository) {}

    public record BackupObservation(
            boolean exists,
            String phase,
            String message,
            Long sizeBytes,
            Instant startedAt,
            Instant completedAt,
            String uid,
            Instant expiration,
            String parentBackupName,
            String baseBackupName,
            Instant coverageStart,
            Instant coverageEnd
    ) {
        /** Backward-compatible constructor for callers that include expiration only. */
        public BackupObservation(boolean exists, String phase, String message, Long sizeBytes,
                                 Instant startedAt, Instant completedAt, String uid,
                                 Instant expiration) {
            this(exists, phase, message, sizeBytes, startedAt, completedAt, uid,
                    expiration, null, null, null, null);
        }
        /** Backward-compatible constructor for callers that do not need expiration. */
        public BackupObservation(boolean exists, String phase, String message, Long sizeBytes,
                                 Instant startedAt, Instant completedAt, String uid) {
            this(exists, phase, message, sizeBytes, startedAt, completedAt, uid,
                    null, null, null, null, null);
        }
        /** Backward-compatible constructor for callers that do not need the Kubernetes UID. */
        public BackupObservation(boolean exists, String phase, String message, Long sizeBytes,
                                 Instant startedAt, Instant completedAt) {
            this(exists, phase, message, sizeBytes, startedAt, completedAt, null,
                    null, null, null, null, null);
        }
        public static BackupObservation missing() {
            return new BackupObservation(false, "Missing", "KubeBlocks Backup was not found",
                    null, null, null, null, null, null, null, null, null);
        }
    }

    public record RestoreObservation(
            boolean exists,
            String restoreName,
            String phase,
            String message,
            Instant startedAt,
            Instant completedAt
    ) {
        public static RestoreObservation missing() {
            return new RestoreObservation(false, null, "Missing",
                    "Waiting for KubeBlocks Restore resource", null, null);
        }
    }

    public record BackupScheduleInfo(boolean exists, String scheduleName,
                                     String observedStatus, boolean available) {
        public static BackupScheduleInfo missing() {
            return new BackupScheduleInfo(false, null, "MISSING", false);
        }
    }

    public record ScheduledBackupInfo(
            String backupName,
            String uid,
            String backupMethod,
            String retentionPeriod,
            String phase,
            String message,
            Long sizeBytes,
            Instant startedAt,
            Instant completedAt
    ) {}

    public enum GeneratedBackupKind { FULL, CONTINUOUS }

    /** Safe lifecycle fields from an automatically generated KubeBlocks Backup CR. */
    public record GeneratedBackupInfo(
            String backupName,
            String uid,
            GeneratedBackupKind kind,
            String backupMethod,
            String retentionPeriod,
            String baseBackupName,
            String parentBackupName,
            String phase,
            String message,
            Long sizeBytes,
            Instant startedAt,
            Instant completedAt,
            Instant coverageStart,
            Instant coverageEnd
    ) {}

    public record VerticalScalingObservation(
            boolean complete,
            int expectedPods,
            int observedPods,
            int matchingReadyPods,
            String message
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
