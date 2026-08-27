package com.cyfuture.dbaas.client;

import com.cyfuture.dbaas.config.DatabaseProperties;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.openapi.apis.StorageV1Api;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KubeBlocksClientTest {
    private CustomObjectsApi customObjectsApi;
    private KubeBlocksClient client;

    @BeforeEach
    void setUp() throws Exception {
        customObjectsApi = mock(CustomObjectsApi.class, RETURNS_DEEP_STUBS);
        client = new KubeBlocksClient(new DatabaseProperties(), customObjectsApi,
                mock(CoreV1Api.class), mock(StorageV1Api.class));
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

    private Map<String, Object> component(String name, int replicas, Map<String, String> volumes) {
        return Map.of(
                "name", name,
                "replicas", replicas,
                "volumeClaimTemplates", volumes.entrySet().stream()
                        .map(entry -> Map.of("name", entry.getKey(), "spec", Map.of(
                                "resources", Map.of("requests", Map.of("storage", entry.getValue())))))
                        .toList());
    }
}
