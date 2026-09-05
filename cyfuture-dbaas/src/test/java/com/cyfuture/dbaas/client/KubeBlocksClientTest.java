package com.cyfuture.dbaas.client;

import com.cyfuture.dbaas.config.DatabaseProperties;
import com.cyfuture.dbaas.dto.CreateDatabaseRequest;
import com.cyfuture.dbaas.model.DatabaseEngine;
import com.cyfuture.dbaas.model.DatabaseMode;
import com.cyfuture.dbaas.model.SizePlan;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.openapi.apis.StorageV1Api;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodCondition;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.openapi.models.V1PodSpec;
import io.kubernetes.client.openapi.models.V1PodStatus;
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaimList;
import io.kubernetes.client.openapi.models.V1ResourceRequirements;
import io.kubernetes.client.openapi.models.V1Namespace;
import io.kubernetes.client.custom.Quantity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KubeBlocksClientTest {
    private CustomObjectsApi customObjectsApi;
    private CoreV1Api coreV1Api;
    private KubeBlocksClient client;

    @BeforeEach
    void setUp() throws Exception {
        customObjectsApi = mock(CustomObjectsApi.class, RETURNS_DEEP_STUBS);
        coreV1Api = mock(CoreV1Api.class, RETURNS_DEEP_STUBS);
        client = new KubeBlocksClient(new DatabaseProperties(), customObjectsApi,
                coreV1Api, mock(StorageV1Api.class));
        when(customObjectsApi.getClusterCustomObject("apiextensions.k8s.io", "v1",
                "customresourcedefinitions", "opsrequests.operations.kubeblocks.io")
                .execute()).thenReturn(opsRequestCrd());
        when(customObjectsApi.createNamespacedCustomObject(eq("operations.kubeblocks.io"),
                eq("v1alpha1"), eq("dbaas-orders"), eq("opsrequests"), any()).execute())
                .thenReturn(Map.of());
    }

    @Test
    void verticalScalingManifestMatchesInstalledOpsRequestSchema() throws Exception {
        client.createVerticalScalingOpsRequest("dbaas-orders", "db-orders0001",
                "op-scale0001", "postgresql",
                Map.of("cpu", "1", "memory", "2Gi"),
                Map.of("cpu", "2", "memory", "4Gi"));

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(customObjectsApi).createNamespacedCustomObject(eq("operations.kubeblocks.io"),
                eq("v1alpha1"), eq("dbaas-orders"), eq("opsrequests"), body.capture());
        Map<?, ?> manifest = (Map<?, ?>) body.getValue();
        Map<?, ?> spec = (Map<?, ?>) manifest.get("spec");
        assertEquals("VerticalScaling", spec.get("type"));
        assertEquals("db-orders0001", spec.get("clusterName"));
        List<?> scaling = (List<?>) spec.get("verticalScaling");
        Map<?, ?> component = (Map<?, ?>) scaling.get(0);
        assertEquals("postgresql", component.get("componentName"));
        assertEquals(Map.of("cpu", "1", "memory", "2Gi"), component.get("requests"));
        assertEquals(Map.of("cpu", "2", "memory", "4Gi"), component.get("limits"));
    }

    @Test
    void horizontalScalingUsesReplicaChangeDelta() throws Exception {
        client.createHorizontalScalingOpsRequest("dbaas-orders", "db-orders0001",
                "op-scale0002", "postgresql", 2, 4);

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(customObjectsApi).createNamespacedCustomObject(eq("operations.kubeblocks.io"),
                eq("v1alpha1"), eq("dbaas-orders"), eq("opsrequests"), body.capture());
        Map<?, ?> spec = (Map<?, ?>) ((Map<?, ?>) body.getValue()).get("spec");
        Map<?, ?> component = (Map<?, ?>) ((List<?>) spec.get("horizontalScaling")).get(0);
        assertEquals(Map.of("replicaChanges", 2), component.get("scaleOut"));
    }

    @Test
    void migratesStrictInPlacePolicyToPreferInPlaceBeforeVerticalScaling() throws Exception {
        when(customObjectsApi.getNamespacedCustomObject("apps.kubeblocks.io", "v1",
                "dbaas-orders", "clusters", "db-orders0001").execute())
                .thenReturn(clusterWithPolicy("StrictInPlace"));
        when(customObjectsApi.replaceNamespacedCustomObject(eq("apps.kubeblocks.io"),
                eq("v1"), eq("dbaas-orders"), eq("clusters"),
                eq("db-orders0001"), any()).execute()).thenReturn(Map.of());

        client.ensurePreferInPlacePodUpdatePolicy("dbaas-orders",
                "db-orders0001", "postgresql");

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(customObjectsApi).replaceNamespacedCustomObject(eq("apps.kubeblocks.io"),
                eq("v1"), eq("dbaas-orders"), eq("clusters"),
                eq("db-orders0001"), body.capture());
        Map<?, ?> spec = (Map<?, ?>) ((Map<?, ?>) body.getValue()).get("spec");
        Map<?, ?> component = (Map<?, ?>) ((List<?>) spec.get("componentSpecs")).get(0);
        assertEquals("PreferInPlace", component.get("podUpdatePolicy"));
    }

    @Test
    void extractsComponentsFromNormalAndShardedClusters() throws Exception {
        when(customObjectsApi.getNamespacedCustomObject("apps.kubeblocks.io", "v1",
                "dbaas-orders", "clusters", "db-orders0001").execute())
                .thenReturn(cluster());

        List<KubeBlocksClient.ClusterComponentInfo> components = client.components(
                "dbaas-orders", "db-orders0001");

        assertEquals(List.of("mongos", "config-server", "shard"),
                components.stream().map(KubeBlocksClient.ClusterComponentInfo::name).toList());
        assertTrue(components.get(2).sharding());
        assertEquals("20Gi", components.get(2).storage("data"));
        assertEquals("StrictInPlace", components.get(2).podUpdatePolicy());
    }

    @Test
    void createsPostgresqlMongoAndMysqlWithPreferInPlacePolicy() throws Exception {
        client.create("dbaas-orders", "prj-orders", "db-postgres0001",
                request(DatabaseEngine.POSTGRESQL, DatabaseMode.REPLICATION));
        client.create("dbaas-orders", "prj-orders", "db-mongo000001",
                request(DatabaseEngine.MONGODB, DatabaseMode.REPLICA_SET));
        client.create("dbaas-orders", "prj-orders", "db-mysql000001",
                request(DatabaseEngine.MYSQL, DatabaseMode.REPLICATION));

        ArgumentCaptor<Object> bodies = ArgumentCaptor.forClass(Object.class);
        verify(customObjectsApi, times(3)).createNamespacedCustomObject(
                eq("apps.kubeblocks.io"), eq("v1"), eq("dbaas-orders"), eq("clusters"), bodies.capture());
        for (Object body : bodies.getAllValues()) {
            Map<?, ?> spec = (Map<?, ?>) ((Map<?, ?>) body).get("spec");
            for (Object component : (List<?>) spec.get("componentSpecs")) {
                assertEquals("PreferInPlace", ((Map<?, ?>) component).get("podUpdatePolicy"));
            }
        }
    }

    @Test
    void migratesEveryManagedStrictInPlaceComponentWithoutTouchingUnmanagedClusters() throws Exception {
        Map<String, Object> managed = clusterWithPolicy("StrictInPlace");
        managed.put("metadata", new java.util.LinkedHashMap<>(Map.of(
                "name", "db-orders0001", "namespace", "dbaas-orders",
                "labels", Map.of("app.kubernetes.io/managed-by", "cyfuture-dbaas"))));
        Map<String, Object> unmanaged = clusterWithPolicy("StrictInPlace");
        unmanaged.put("metadata", Map.of("name", "other", "namespace", "default", "labels", Map.of()));
        when(customObjectsApi.listClusterCustomObject("apps.kubeblocks.io", "v1", "clusters")
                .execute()).thenReturn(Map.of("items", List.of(managed, unmanaged)));
        when(customObjectsApi.replaceNamespacedCustomObject(eq("apps.kubeblocks.io"), eq("v1"),
                eq("dbaas-orders"), eq("clusters"), eq("db-orders0001"), any()).execute())
                .thenReturn(Map.of());

        assertEquals(1, client.migrateManagedClustersToPreferInPlace());
        Map<?, ?> component = (Map<?, ?>) ((List<?>) ((Map<?, ?>) managed.get("spec"))
                .get("componentSpecs")).get(0);
        assertEquals("PreferInPlace", component.get("podUpdatePolicy"));
    }

    @Test
    void verifiesActualRequestedPodResourcesBeforeCompletingVerticalScaling() throws Exception {
        when(customObjectsApi.getNamespacedCustomObject("apps.kubeblocks.io", "v1",
                "dbaas-orders", "clusters", "db-orders0001").execute())
                .thenReturn(clusterWithPolicy("PreferInPlace"));
        V1ResourceRequirements resources = new V1ResourceRequirements()
                .requests(Map.of("cpu", new Quantity("1000m"), "memory", new Quantity("2048Mi")))
                .limits(Map.of("cpu", new Quantity("2"), "memory", new Quantity("4Gi")));
        V1Pod pod = new V1Pod()
                .metadata(new V1ObjectMeta().name("db-orders0001-postgresql-0").labels(Map.of(
                        "app.kubernetes.io/instance", "db-orders0001",
                        "apps.kubeblocks.io/component-name", "postgresql")))
                .spec(new V1PodSpec().containers(List.of(new V1Container()
                        .name("postgresql").resources(resources))))
                .status(new V1PodStatus().conditions(List.of(new V1PodCondition()
                        .type("Ready").status("True"))));
        when(coreV1Api.listNamespacedPod("dbaas-orders")
                .labelSelector("app.kubernetes.io/instance=db-orders0001").execute())
                .thenReturn(new V1PodList().items(List.of(pod, pod)));

        KubeBlocksClient.VerticalScalingObservation observation = client.observeVerticalScaling(
                "dbaas-orders", "db-orders0001", "postgresql",
                Map.of("cpu", "1", "memory", "2Gi"),
                Map.of("cpu", "2", "memory", "4Gi"));

        assertTrue(observation.complete());
    }

    @Test
    void updatesOnlyDeletionProtectionFields() throws Exception {
        when(customObjectsApi.getNamespacedCustomObject("apps.kubeblocks.io", "v1",
                "dbaas-orders", "clusters", "db-orders0001").execute())
                .thenReturn(observableCluster());
        when(coreV1Api.listNamespacedPod("dbaas-orders")
                .labelSelector("app.kubernetes.io/instance=db-orders0001").execute())
                .thenReturn(new V1PodList().items(List.of()));
        when(coreV1Api.listNamespacedPersistentVolumeClaim("dbaas-orders")
                .labelSelector("app.kubernetes.io/instance=db-orders0001").execute())
                .thenReturn(new V1PersistentVolumeClaimList().items(List.of()));

        client.setDeletionProtection("dbaas-orders", "db-orders0001", true);

        ArgumentCaptor<Object> replacement = ArgumentCaptor.forClass(Object.class);
        verify(customObjectsApi).replaceNamespacedCustomObject(eq("apps.kubeblocks.io"), eq("v1"),
                eq("dbaas-orders"), eq("clusters"), eq("db-orders0001"), replacement.capture());
        Map<?, ?> body = (Map<?, ?>) replacement.getValue();
        assertEquals("DoNotTerminate", ((Map<?, ?>) body.get("spec")).get("terminationPolicy"));
        Map<?, ?> metadata = (Map<?, ?>) body.get("metadata");
        Map<?, ?> annotations = (Map<?, ?>) metadata.get("annotations");
        assertEquals("true", annotations.get("dbaas.cyfuture.com/deletion-protection"));
    }

    @Test
    void deletesOnlyANamespaceOwnedByTheProject() throws Exception {
        when(coreV1Api.readNamespace("dbaas-p-prj-orders0001").execute()).thenReturn(
                new V1Namespace().metadata(new V1ObjectMeta().labels(Map.of(
                        "app.kubernetes.io/managed-by", "cyfuture-dbaas",
                        "dbaas.cyfuture.com/project", "prj-orders0001"))));

        client.deleteProjectNamespace("dbaas-p-prj-orders0001", "prj-orders0001");

        verify(coreV1Api).deleteNamespace("dbaas-p-prj-orders0001");
    }

    @Test
    void projectDeletionClearsProtectionBeforeDeletingTheCluster() throws Exception {
        when(customObjectsApi.getNamespacedCustomObject("apps.kubeblocks.io", "v1",
                "dbaas-orders", "clusters", "db-orders0001").execute())
                .thenReturn(observableCluster());

        client.prepareProjectDatabaseDeletion("dbaas-orders", "db-orders0001");

        ArgumentCaptor<Object> replacement = ArgumentCaptor.forClass(Object.class);
        verify(customObjectsApi).replaceNamespacedCustomObject(eq("apps.kubeblocks.io"), eq("v1"),
                eq("dbaas-orders"), eq("clusters"), eq("db-orders0001"), replacement.capture());
        Map<?, ?> body = (Map<?, ?>) replacement.getValue();
        assertEquals("Delete", ((Map<?, ?>) body.get("spec")).get("terminationPolicy"));
        Map<?, ?> annotations = (Map<?, ?>) ((Map<?, ?>) body.get("metadata"))
                .get("annotations");
        assertEquals("false", annotations.get("dbaas.cyfuture.com/deletion-protection"));
        verify(customObjectsApi).deleteNamespacedCustomObject(
                "apps.kubeblocks.io", "v1", "dbaas-orders", "clusters", "db-orders0001");
    }

    private CreateDatabaseRequest request(DatabaseEngine engine, DatabaseMode mode) {
        return new CreateDatabaseRequest("orders-db", null, engine, mode, "test-version",
                SizePlan.C1G1, 10, 2, 0, null, List.of(), false, Map.of());
    }

    private Map<String, Object> opsRequestCrd() {
        return Map.of("spec", Map.of("versions", List.of(Map.of(
                "name", "v1alpha1",
                "schema", Map.of("openAPIV3Schema", Map.of("properties", Map.of(
                        "spec", Map.of("properties", Map.of(
                                "clusterName", Map.of("type", "string"),
                                "type", Map.of("type", "string"),
                                "verticalScaling", Map.of("type", "array"),
                                "horizontalScaling", Map.of("type", "array"),
                                "volumeExpansion", Map.of("type", "array"),
                                "restart", Map.of("type", "array"))))))))));
    }

    private Map<String, Object> cluster() {
        return Map.of("spec", Map.of(
                "componentSpecs", List.of(
                        component("mongos", 2, Map.of()),
                        component("config-server", 3, Map.of("data", "10Gi"))),
                "shardings", List.of(Map.of(
                        "name", "shard",
                        "shards", 2,
                        "template", component("shard", 3, Map.of("data", "20Gi"))))));
    }

    private Map<String, Object> clusterWithPolicy(String policy) {
        Map<String, Object> component = new java.util.LinkedHashMap<>();
        component.put("name", "postgresql");
        component.put("replicas", 2);
        component.put("podUpdatePolicy", policy);
        component.put("volumeClaimTemplates", List.of());
        Map<String, Object> spec = new java.util.LinkedHashMap<>();
        spec.put("componentSpecs", List.of(component));
        Map<String, Object> cluster = new java.util.LinkedHashMap<>();
        cluster.put("spec", spec);
        return cluster;
    }

    private Map<String, Object> observableCluster() {
        return Map.of(
                "metadata", Map.of("name", "db-orders0001", "annotations", Map.of(
                        "dbaas.cyfuture.com/engine", "POSTGRESQL",
                        "dbaas.cyfuture.com/mode", "STANDALONE",
                        "dbaas.cyfuture.com/version", "17.5.0",
                        "dbaas.cyfuture.com/size", "C1G1",
                        "dbaas.cyfuture.com/storage-gi", "10")),
                "spec", Map.of("componentSpecs", List.of(Map.of(
                        "name", "postgresql", "replicas", 1))),
                "status", Map.of("phase", "Running"));
    }

    private Map<String, Object> component(String name, int replicas, Map<String, String> volumes) {
        return Map.of(
                "name", name,
                "replicas", replicas,
                "podUpdatePolicy", "StrictInPlace",
                "volumeClaimTemplates", volumes.entrySet().stream()
                        .map(entry -> Map.of("name", entry.getKey(), "spec", Map.of(
                                "resources", Map.of("requests", Map.of("storage", entry.getValue())))))
                        .toList());
    }
}
