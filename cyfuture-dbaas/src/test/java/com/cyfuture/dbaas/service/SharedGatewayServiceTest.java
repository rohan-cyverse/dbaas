package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.client.DatabaseObservation;
import com.cyfuture.dbaas.config.DatabaseProperties;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.model.DatabaseEngine;
import com.cyfuture.dbaas.model.DatabaseMode;
import com.cyfuture.dbaas.model.DatabaseStatus;
import com.cyfuture.dbaas.model.SizePlan;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1DeploymentSpec;
import io.kubernetes.client.openapi.models.V1DeploymentStatus;
import io.kubernetes.client.openapi.models.V1LoadBalancerIngress;
import io.kubernetes.client.openapi.models.V1LoadBalancerStatus;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1PodTemplateSpec;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.openapi.models.V1ServicePort;
import io.kubernetes.client.openapi.models.V1ServiceSpec;
import io.kubernetes.client.openapi.models.V1ServiceStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SharedGatewayServiceTest {

    @Test
    void disabledInstancesNeverAcquireTheGatewayLock() {
        DatabaseProperties properties = new DatabaseProperties();
        GatewayReconciliationLock lock = mock(GatewayReconciliationLock.class);
        SharedGatewayService service = service(properties, lock);

        assertFalse(properties.getGateway().isReconcileEnabled());
        service.reconcileNow();
        service.scheduledReconcile();

        verifyNoInteractions(lock);
    }

    @Test
    void enabledInstancesReconcileThroughTheMySqlLock() {
        DatabaseProperties properties = new DatabaseProperties();
        properties.getGateway().setReconcileEnabled(true);
        GatewayReconciliationLock lock = mock(GatewayReconciliationLock.class);
        SharedGatewayService service = service(properties, lock);

        service.reconcileNow();

        verify(lock).execute(any(Runnable.class));
    }

    @Test
    void reconcileAddsMissingServicePortsAndPreservesExistingNodePorts() throws Exception {
        DatabaseProperties properties = enabledProperties();
        DatabaseMetadataRepository repository = mock(DatabaseMetadataRepository.class);
        CoreV1Api core = mock(CoreV1Api.class, RETURNS_DEEP_STUBS);
        AppsV1Api apps = mock(AppsV1Api.class, RETURNS_DEEP_STUBS);
        V1Service service = gatewayService(31000, 31009);
        when(core.readNamespacedService("dbaas-public-gateway", "dbaas-gateway").execute()).thenReturn(service);
        when(core.readNamespacedConfigMap("dbaas-public-gateway-config", "dbaas-gateway").execute())
                .thenReturn(configMap(renderedEmptyGateway()));
        when(apps.readNamespacedDeployment("dbaas-public-gateway", "dbaas-gateway").execute())
                .thenReturn(deployment());
        when(repository.findByPublicPortIsNotNullOrderByPublicPortAsc()).thenReturn(List.of());
        GatewayReconciliationLock lock = runningLock();
        SharedGatewayService sharedGateway = service(properties, lock, repository, core, apps,
                mock(KubeBlocksClient.class), mock(DatabaseBackendResolver.class));

        sharedGateway.reconcileNow();

        ArgumentCaptor<V1Service> replacement = ArgumentCaptor.forClass(V1Service.class);
        verify(core).replaceNamespacedService(
                any(), any(), replacement.capture());
        V1Service updated = replacement.getValue();
        assertEquals(31, updated.getSpec().getPorts().size());
        assertEquals(32000, port(updated, 31000).getNodePort());
        assertEquals(32009, port(updated, 31009).getNodePort());
        V1ServicePort added = port(updated, 31010);
        assertEquals("db-31010", added.getName());
        assertEquals(31010, added.getPort());
        assertEquals("TCP", added.getProtocol());
        assertEquals(31010, added.getTargetPort().getIntValue());
        assertNull(added.getNodePort());
        assertEquals("LoadBalancer", updated.getSpec().getType());
        assertEquals(Map.of("app", "haproxy"), updated.getSpec().getSelector());
        assertEquals("Local", updated.getSpec().getExternalTrafficPolicy());
        assertTrue(updated.getSpec().getLoadBalancerSourceRanges().isEmpty());
        assertEquals("203.0.113.10", updated.getStatus().getLoadBalancer().getIngress().get(0).getIp());
        assertEquals("false", updated.getMetadata().getAnnotations()
                .get("loadbalancer.openstack.org/proxy-protocol"));
    }

    @Test
    void reconcileEnforcesExactProxyAnnotationAndRemovesPortsAboveRange() throws Exception {
        DatabaseProperties properties = enabledProperties();
        DatabaseMetadataRepository repository = mock(DatabaseMetadataRepository.class);
        CoreV1Api core = mock(CoreV1Api.class, RETURNS_DEEP_STUBS);
        AppsV1Api apps = mock(AppsV1Api.class, RETURNS_DEEP_STUBS);
        V1Service service = gatewayService(31000, 31031);
        service.getMetadata().setAnnotations(Map.of("loadbalancer.openstack.org/proxy-protocol", "v2"));
        when(core.readNamespacedService("dbaas-public-gateway", "dbaas-gateway").execute()).thenReturn(service);
        when(core.readNamespacedConfigMap("dbaas-public-gateway-config", "dbaas-gateway").execute())
                .thenReturn(configMap(renderedEmptyGateway()));
        when(apps.readNamespacedDeployment("dbaas-public-gateway", "dbaas-gateway").execute())
                .thenReturn(deployment());
        when(repository.findByPublicPortIsNotNullOrderByPublicPortAsc()).thenReturn(List.of());
        SharedGatewayService sharedGateway = service(properties, runningLock(), repository, core, apps,
                mock(KubeBlocksClient.class), mock(DatabaseBackendResolver.class));

        sharedGateway.reconcileNow();

        ArgumentCaptor<V1Service> replacement = ArgumentCaptor.forClass(V1Service.class);
        verify(core).replaceNamespacedService(any(), any(), replacement.capture());
        V1Service updated = replacement.getValue();
        assertEquals(31, updated.getSpec().getPorts().size());
        assertTrue(updated.getSpec().getLoadBalancerSourceRanges().isEmpty());
        assertEquals("false", updated.getMetadata().getAnnotations()
                .get("loadbalancer.openstack.org/proxy-protocol"));
        assertEquals(32000, port(updated, 31000).getNodePort());
        assertEquals(32030, port(updated, 31030).getNodePort());
        assertTrue(updated.getSpec().getPorts().stream()
                .noneMatch(candidate -> Integer.valueOf(31031).equals(candidate.getPort())));
        assertEquals("203.0.113.10", updated.getStatus().getLoadBalancer().getIngress().get(0).getIp());
    }

    @Test
    void unchangedGatewayStateDoesNotUpdateKubernetesObjects() throws Exception {
        DatabaseProperties properties = enabledProperties();
        DatabaseMetadataRepository repository = mock(DatabaseMetadataRepository.class);
        CoreV1Api core = mock(CoreV1Api.class, RETURNS_DEEP_STUBS);
        AppsV1Api apps = mock(AppsV1Api.class, RETURNS_DEEP_STUBS);
        V1Service service = gatewayService(31000, 31030);
        service.getSpec().setLoadBalancerSourceRanges(List.of());
        when(core.readNamespacedService("dbaas-public-gateway", "dbaas-gateway").execute())
                .thenReturn(service);
        when(core.readNamespacedConfigMap("dbaas-public-gateway-config", "dbaas-gateway").execute())
                .thenReturn(configMap(renderedEmptyGateway()));
        when(apps.readNamespacedDeployment("dbaas-public-gateway", "dbaas-gateway").execute())
                .thenReturn(deployment());
        when(repository.findByPublicPortIsNotNullOrderByPublicPortAsc()).thenReturn(List.of());
        SharedGatewayService sharedGateway = service(properties, runningLock(), repository, core, apps,
                mock(KubeBlocksClient.class), mock(DatabaseBackendResolver.class));

        sharedGateway.reconcileNow();

        verify(core, never()).replaceNamespacedService(any(), any(), any());
        verify(core, never()).replaceNamespacedConfigMap(any(), any(), any());
        verify(apps, never()).replaceNamespacedDeployment(any(), any(), any());
    }

    @Test
    void haproxyRendersConfiguredRangeAndOnlyActiveRoutes() throws Exception {
        DatabaseProperties properties = enabledProperties();
        DatabaseMetadata database = database(31030);
        DatabaseMetadataRepository repository = mock(DatabaseMetadataRepository.class);
        KubeBlocksClient kubeBlocksClient = mock(KubeBlocksClient.class);
        DatabaseBackendResolver backendResolver = mock(DatabaseBackendResolver.class);
        CoreV1Api core = mock(CoreV1Api.class, RETURNS_DEEP_STUBS);
        AppsV1Api apps = mock(AppsV1Api.class, RETURNS_DEEP_STUBS);
        when(core.readNamespacedService("dbaas-public-gateway", "dbaas-gateway").execute())
                .thenReturn(gatewayService(31000, 31030));
        when(core.readNamespacedConfigMap("dbaas-public-gateway-config", "dbaas-gateway").execute())
                .thenReturn(configMap("old"));
        when(apps.readNamespacedDeployment("dbaas-public-gateway", "dbaas-gateway").execute())
                .thenReturn(deployment());
        when(repository.findByPublicPortIsNotNullOrderByPublicPortAsc()).thenReturn(List.of(database));
        when(kubeBlocksClient.get("dbaas-orders", "db-orders0001"))
                .thenReturn(observation());
        when(backendResolver.resolve(database)).thenReturn(
                new DatabaseBackendResolver.DatabaseBackendEndpoint(
                        "db-orders0001-postgresql", "dbaas-orders",
                        "db-orders0001-postgresql.dbaas-orders.svc.cluster.local", 5432));
        SharedGatewayService sharedGateway = service(properties, runningLock(), repository, core, apps,
                kubeBlocksClient, backendResolver);

        sharedGateway.reconcileNow();

        ArgumentCaptor<V1ConfigMap> replacement = ArgumentCaptor.forClass(V1ConfigMap.class);
        verify(core).replaceNamespacedConfigMap(any(), any(), replacement.capture());
        String rendered = replacement.getValue().getData().get("haproxy.cfg");
        assertTrue(rendered.contains("bind *:31000-31030"));
        assertFalse(rendered.contains("accept-proxy"));
        assertTrue(rendered.contains("acl configured_port dst_port 31030"));
        assertTrue(rendered.contains("backend database_31030"));
        assertFalse(rendered.contains("acl allowed_31030 src"));
        assertFalse(rendered.contains("!allowed_31030"));
        assertTrue(rendered.indexOf("acl configured_port") < rendered.indexOf("acl port_31030"));
        assertTrue(rendered.indexOf("acl port_31030") < rendered.indexOf("tcp-request content reject"));
        assertTrue(rendered.indexOf("tcp-request content reject") < rendered.indexOf("use_backend database_31030"));
        assertTrue(rendered.indexOf("use_backend database_31030") < rendered.indexOf("backend database_31030"));
        assertNoMissingAclReferences(rendered);
        assertFalse(rendered.contains("backend database_31010"));
        assertFalse(rendered.contains("backend database_31031"));
    }

    @Test
    void configMapChangeTriggersOneChecksumBasedDeploymentRollout() throws Exception {
        DatabaseProperties properties = enabledProperties();
        DatabaseMetadata database = database(31000);
        DatabaseMetadataRepository repository = mock(DatabaseMetadataRepository.class);
        KubeBlocksClient kubeBlocksClient = mock(KubeBlocksClient.class);
        DatabaseBackendResolver backendResolver = mock(DatabaseBackendResolver.class);
        CoreV1Api core = mock(CoreV1Api.class, RETURNS_DEEP_STUBS);
        AppsV1Api apps = mock(AppsV1Api.class, RETURNS_DEEP_STUBS);
        when(core.readNamespacedService(any(), any()).execute()).thenReturn(gatewayService(31000, 31030));
        when(core.readNamespacedConfigMap(any(), any()).execute()).thenReturn(configMap("old"));
        when(apps.readNamespacedDeployment(any(), any()).execute()).thenReturn(deployment());
        when(repository.findByPublicPortIsNotNullOrderByPublicPortAsc()).thenReturn(List.of(database));
        when(kubeBlocksClient.get(any(), any())).thenReturn(observation());
        when(backendResolver.resolve(database)).thenReturn(new DatabaseBackendResolver.DatabaseBackendEndpoint(
                "orders", "dbaas-orders", "orders.dbaas-orders.svc.cluster.local", 5432));
        SharedGatewayService sharedGateway = service(properties, runningLock(), repository, core, apps,
                kubeBlocksClient, backendResolver);

        sharedGateway.reconcileNow();

        ArgumentCaptor<V1ConfigMap> configReplacement = ArgumentCaptor.forClass(V1ConfigMap.class);
        ArgumentCaptor<V1Deployment> deploymentReplacement = ArgumentCaptor.forClass(V1Deployment.class);
        verify(core).replaceNamespacedConfigMap(any(), any(), configReplacement.capture());
        verify(apps, times(1)).replaceNamespacedDeployment(any(), any(), deploymentReplacement.capture());
        String rendered = configReplacement.getValue().getData().get("haproxy.cfg");
        assertEquals(sha256(rendered), deploymentReplacement.getValue().getSpec().getTemplate()
                .getMetadata().getAnnotations().get("dbaas.cyfuture.com/config-checksum"));
    }

    @Test
    void cidrOnlyServiceUpdateDoesNotRolloutHaproxyDeployment() throws Exception {
        DatabaseProperties properties = enabledProperties();
        DatabaseMetadata database = database(31000);
        database.setAllowedCidrs("[49.50.73.146/32, 157.49.126.77/32]");
        String config = renderedRoute(database, 31000);
        DatabaseMetadataRepository repository = mock(DatabaseMetadataRepository.class);
        KubeBlocksClient kubeBlocksClient = mock(KubeBlocksClient.class);
        DatabaseBackendResolver backendResolver = mock(DatabaseBackendResolver.class);
        CoreV1Api core = mock(CoreV1Api.class, RETURNS_DEEP_STUBS);
        AppsV1Api apps = mock(AppsV1Api.class, RETURNS_DEEP_STUBS);
        V1Service service = gatewayService(31000, 31030);
        service.getSpec().setLoadBalancerSourceRanges(List.of("49.50.73.146/32"));
        when(core.readNamespacedService(any(), any()).execute()).thenReturn(service);
        when(core.readNamespacedConfigMap(any(), any()).execute()).thenReturn(configMap(config));
        when(apps.readNamespacedDeployment(any(), any()).execute())
                .thenReturn(deployment(sha256(config), 2, 2));
        when(repository.findByPublicPortIsNotNullOrderByPublicPortAsc()).thenReturn(List.of(database));
        when(kubeBlocksClient.get(any(), any())).thenReturn(observation());
        when(backendResolver.resolve(database)).thenReturn(new DatabaseBackendResolver.DatabaseBackendEndpoint(
                "orders", "dbaas-orders",
                "db-orders0001-postgresql.dbaas-orders.svc.cluster.local", 5432));
        SharedGatewayService sharedGateway = service(properties, runningLock(), repository, core, apps,
                kubeBlocksClient, backendResolver);

        sharedGateway.reconcileNow();

        ArgumentCaptor<V1Service> serviceReplacement = ArgumentCaptor.forClass(V1Service.class);
        verify(core).replaceNamespacedService(any(), any(), serviceReplacement.capture());
        assertEquals(List.of("157.49.126.77/32", "49.50.73.146/32"),
                serviceReplacement.getValue().getSpec().getLoadBalancerSourceRanges());
        verify(core, never()).replaceNamespacedConfigMap(any(), any(), any());
        verify(apps, never()).replaceNamespacedDeployment(any(), any(), any());
    }

    @Test
    void endpointIsReadyOnlyAfterDatabaseServiceRouteAndDeploymentAreReady() throws Exception {
        DatabaseProperties properties = enabledProperties();
        DatabaseMetadata database = database(31000);
        String config = renderedRoute(database, 31000);
        DatabaseMetadataRepository repository = mock(DatabaseMetadataRepository.class);
        KubeBlocksClient kubeBlocksClient = mock(KubeBlocksClient.class);
        DatabaseBackendResolver backendResolver = mock(DatabaseBackendResolver.class);
        CoreV1Api core = mock(CoreV1Api.class, RETURNS_DEEP_STUBS);
        AppsV1Api apps = mock(AppsV1Api.class, RETURNS_DEEP_STUBS);
        when(core.readNamespacedService(any(), any()).execute()).thenReturn(gatewayService(31000, 31030));
        when(core.readNamespacedConfigMap(any(), any()).execute()).thenReturn(configMap(config));
        when(apps.readNamespacedDeployment(any(), any()).execute()).thenReturn(deployment(sha256(config), 2, 2));
        when(kubeBlocksClient.get(database.getNamespaceName(), database.physicalClusterName()))
                .thenReturn(observationWithServiceReady(false), observation());
        when(backendResolver.resolve(database)).thenReturn(new DatabaseBackendResolver.DatabaseBackendEndpoint(
                "orders", "dbaas-orders",
                "db-orders0001-postgresql.dbaas-orders.svc.cluster.local", 5432));
        SharedGatewayService sharedGateway = service(properties, runningLock(), repository, core, apps,
                kubeBlocksClient, backendResolver);

        assertFalse(sharedGateway.endpoint(database).ready());
        assertTrue(sharedGateway.endpoint(database).ready());
    }

    @Test
    void endpointIsNotReadyUntilLoadBalancerSourceRangesContainDatabaseCidrs() throws Exception {
        DatabaseProperties properties = enabledProperties();
        DatabaseMetadata database = database(31000);
        String config = renderedRoute(database, 31000);
        KubeBlocksClient kubeBlocksClient = mock(KubeBlocksClient.class);
        DatabaseBackendResolver backendResolver = mock(DatabaseBackendResolver.class);
        CoreV1Api core = mock(CoreV1Api.class, RETURNS_DEEP_STUBS);
        AppsV1Api apps = mock(AppsV1Api.class, RETURNS_DEEP_STUBS);
        V1Service missingSourceRange = gatewayService(31000, 31030);
        missingSourceRange.getSpec().setLoadBalancerSourceRanges(List.of("203.0.113.10/32"));
        when(core.readNamespacedService(any(), any()).execute()).thenReturn(missingSourceRange);
        when(core.readNamespacedConfigMap(any(), any()).execute()).thenReturn(configMap(config));
        when(apps.readNamespacedDeployment(any(), any()).execute()).thenReturn(deployment(sha256(config), 2, 2));
        when(kubeBlocksClient.get(database.getNamespaceName(), database.physicalClusterName()))
                .thenReturn(observation());
        when(backendResolver.resolve(database)).thenReturn(new DatabaseBackendResolver.DatabaseBackendEndpoint(
                "orders", "dbaas-orders",
                "db-orders0001-postgresql.dbaas-orders.svc.cluster.local", 5432));
        SharedGatewayService sharedGateway = service(properties, runningLock(), mock(DatabaseMetadataRepository.class),
                core, apps, kubeBlocksClient, backendResolver);

        assertFalse(sharedGateway.endpoint(database).ready());
    }

    @Test
    void endpointIsNotReadyUntilBackendServiceMatchesResolver() throws Exception {
        DatabaseProperties properties = enabledProperties();
        DatabaseMetadata database = database(31000);
        String config = renderedRoute(database, 31000);
        KubeBlocksClient kubeBlocksClient = mock(KubeBlocksClient.class);
        DatabaseBackendResolver backendResolver = mock(DatabaseBackendResolver.class);
        CoreV1Api core = mock(CoreV1Api.class, RETURNS_DEEP_STUBS);
        AppsV1Api apps = mock(AppsV1Api.class, RETURNS_DEEP_STUBS);
        when(core.readNamespacedService(any(), any()).execute()).thenReturn(gatewayService(31000, 31030));
        when(core.readNamespacedConfigMap(any(), any()).execute()).thenReturn(configMap(config));
        when(apps.readNamespacedDeployment(any(), any()).execute()).thenReturn(deployment(sha256(config), 2, 2));
        when(kubeBlocksClient.get(database.getNamespaceName(), database.physicalClusterName()))
                .thenReturn(observation());
        when(backendResolver.resolve(database)).thenReturn(new DatabaseBackendResolver.DatabaseBackendEndpoint(
                "orders", "dbaas-orders", "new-primary.dbaas-orders.svc.cluster.local", 5432));
        SharedGatewayService sharedGateway = service(properties, runningLock(), mock(DatabaseMetadataRepository.class),
                core, apps, kubeBlocksClient, backendResolver);

        assertFalse(sharedGateway.endpoint(database).ready());
    }

    @Test
    void rendersPortRoutesAndLoadBalancerUnionForEveryDatabasePort() throws Exception {
        DatabaseProperties properties = enabledProperties();
        DatabaseMetadata databaseA = database(31000);
        databaseA.setAllowedCidrs("[49.50.73.146/32]");
        DatabaseMetadata databaseB = database(31001);
        databaseB.setDatabaseId("db-billing0001");
        databaseB.setAllowedCidrs("[47.31.130.31/32, 152.58.123.106/32]");
        DatabaseMetadataRepository repository = mock(DatabaseMetadataRepository.class);
        KubeBlocksClient kubeBlocksClient = mock(KubeBlocksClient.class);
        DatabaseBackendResolver backendResolver = mock(DatabaseBackendResolver.class);
        CoreV1Api core = mock(CoreV1Api.class, RETURNS_DEEP_STUBS);
        AppsV1Api apps = mock(AppsV1Api.class, RETURNS_DEEP_STUBS);
        when(core.readNamespacedService(any(), any()).execute()).thenReturn(gatewayService(31000, 31030));
        when(core.readNamespacedConfigMap(any(), any()).execute()).thenReturn(configMap("old"));
        when(apps.readNamespacedDeployment(any(), any()).execute()).thenReturn(deployment());
        when(repository.findByPublicPortIsNotNullOrderByPublicPortAsc())
                .thenReturn(List.of(databaseA, databaseB));
        when(kubeBlocksClient.get(any(), any())).thenReturn(observation());
        when(backendResolver.resolve(databaseA)).thenReturn(new DatabaseBackendResolver.DatabaseBackendEndpoint(
                "orders", "dbaas-orders", "orders.dbaas-orders.svc.cluster.local", 5432));
        when(backendResolver.resolve(databaseB)).thenReturn(new DatabaseBackendResolver.DatabaseBackendEndpoint(
                "billing", "dbaas-orders", "billing.dbaas-orders.svc.cluster.local", 5432));
        SharedGatewayService sharedGateway = service(properties, runningLock(), repository, core, apps,
                kubeBlocksClient, backendResolver);

        sharedGateway.reconcileNow();

        ArgumentCaptor<V1ConfigMap> replacement = ArgumentCaptor.forClass(V1ConfigMap.class);
        verify(core).replaceNamespacedConfigMap(any(), any(), replacement.capture());
        String rendered = replacement.getValue().getData().get("haproxy.cfg");
        assertFalse(rendered.contains("acl allowed_"));
        assertFalse(rendered.contains(" src "));
        assertFalse(rendered.contains("!allowed_"));
        assertTrue(rendered.contains("use_backend database_31000 if port_31000"));
        assertTrue(rendered.contains("use_backend database_31001 if port_31001"));
        ArgumentCaptor<V1Service> serviceReplacement = ArgumentCaptor.forClass(V1Service.class);
        verify(core).replaceNamespacedService(any(), any(), serviceReplacement.capture());
        assertEquals(List.of("152.58.123.106/32", "47.31.130.31/32", "49.50.73.146/32"),
                serviceReplacement.getValue().getSpec().getLoadBalancerSourceRanges());
    }

    @Test
    void loadBalancerSourceRangesRemoveStaleCidrsAndOpenInternetUnlessActiveRouteAllowsIt() throws Exception {
        DatabaseProperties properties = enabledProperties();
        DatabaseMetadata database = database(31007);
        database.setAllowedCidrs("[160.202.36.173/32]");
        DatabaseMetadataRepository repository = mock(DatabaseMetadataRepository.class);
        KubeBlocksClient kubeBlocksClient = mock(KubeBlocksClient.class);
        DatabaseBackendResolver backendResolver = mock(DatabaseBackendResolver.class);
        CoreV1Api core = mock(CoreV1Api.class, RETURNS_DEEP_STUBS);
        AppsV1Api apps = mock(AppsV1Api.class, RETURNS_DEEP_STUBS);
        V1Service service = gatewayService(31000, 31030);
        service.getSpec().setLoadBalancerSourceRanges(
                List.of("0.0.0.0/0", "160.202.36.173/32", "203.0.113.10/32"));
        when(core.readNamespacedService(any(), any()).execute()).thenReturn(service);
        when(core.readNamespacedConfigMap(any(), any()).execute()).thenReturn(configMap("old"));
        when(apps.readNamespacedDeployment(any(), any()).execute()).thenReturn(deployment());
        when(repository.findByPublicPortIsNotNullOrderByPublicPortAsc()).thenReturn(List.of(database));
        when(kubeBlocksClient.get(any(), any())).thenReturn(observation());
        when(backendResolver.resolve(database)).thenReturn(new DatabaseBackendResolver.DatabaseBackendEndpoint(
                "orders", "dbaas-orders", "orders.dbaas-orders.svc.cluster.local", 5432));
        SharedGatewayService sharedGateway = service(properties, runningLock(), repository, core, apps,
                kubeBlocksClient, backendResolver);

        sharedGateway.reconcileNow();

        ArgumentCaptor<V1Service> replacement = ArgumentCaptor.forClass(V1Service.class);
        verify(core).replaceNamespacedService(any(), any(), replacement.capture());
        assertEquals(List.of("160.202.36.173/32"),
                replacement.getValue().getSpec().getLoadBalancerSourceRanges());
    }

    @Test
    void loadBalancerSourceRangesKeepOpenInternetOnlyWhenActivePublicRouteAllowsIt() throws Exception {
        DatabaseProperties properties = enabledProperties();
        DatabaseMetadata database = database(31007);
        database.setAllowedCidrs("[0.0.0.0/0, 160.202.36.173/32]");
        DatabaseMetadataRepository repository = mock(DatabaseMetadataRepository.class);
        KubeBlocksClient kubeBlocksClient = mock(KubeBlocksClient.class);
        DatabaseBackendResolver backendResolver = mock(DatabaseBackendResolver.class);
        CoreV1Api core = mock(CoreV1Api.class, RETURNS_DEEP_STUBS);
        AppsV1Api apps = mock(AppsV1Api.class, RETURNS_DEEP_STUBS);
        V1Service service = gatewayService(31000, 31030);
        service.getSpec().setLoadBalancerSourceRanges(List.of("160.202.36.173/32"));
        when(core.readNamespacedService(any(), any()).execute()).thenReturn(service);
        when(core.readNamespacedConfigMap(any(), any()).execute()).thenReturn(configMap("old"));
        when(apps.readNamespacedDeployment(any(), any()).execute()).thenReturn(deployment());
        when(repository.findByPublicPortIsNotNullOrderByPublicPortAsc()).thenReturn(List.of(database));
        when(kubeBlocksClient.get(any(), any())).thenReturn(observation());
        when(backendResolver.resolve(database)).thenReturn(new DatabaseBackendResolver.DatabaseBackendEndpoint(
                "orders", "dbaas-orders", "orders.dbaas-orders.svc.cluster.local", 5432));
        SharedGatewayService sharedGateway = service(properties, runningLock(), repository, core, apps,
                kubeBlocksClient, backendResolver);

        sharedGateway.reconcileNow();

        ArgumentCaptor<V1Service> replacement = ArgumentCaptor.forClass(V1Service.class);
        verify(core).replaceNamespacedService(any(), any(), replacement.capture());
        assertEquals(List.of("0.0.0.0/0"),
                replacement.getValue().getSpec().getLoadBalancerSourceRanges());
    }

    @Test
    void privateAndDeletedDatabasesHaveNoRoute() throws Exception {
        DatabaseProperties properties = enabledProperties();
        DatabaseMetadata privateDatabase = database(31000);
        privateDatabase.setPublicPort(null);
        DatabaseMetadata deletedDatabase = database(31001);
        deletedDatabase.setStatus(DatabaseStatus.DELETED);
        DatabaseMetadataRepository repository = mock(DatabaseMetadataRepository.class);
        CoreV1Api core = mock(CoreV1Api.class, RETURNS_DEEP_STUBS);
        AppsV1Api apps = mock(AppsV1Api.class, RETURNS_DEEP_STUBS);
        when(core.readNamespacedService(any(), any()).execute()).thenReturn(gatewayService(31000, 31030));
        when(core.readNamespacedConfigMap(any(), any()).execute()).thenReturn(configMap("old"));
        when(apps.readNamespacedDeployment(any(), any()).execute()).thenReturn(deployment());
        when(repository.findByPublicPortIsNotNullOrderByPublicPortAsc()).thenReturn(List.of(deletedDatabase));
        SharedGatewayService sharedGateway = service(properties, runningLock(), repository, core, apps,
                mock(KubeBlocksClient.class), mock(DatabaseBackendResolver.class));

        sharedGateway.reconcileNow();

        ArgumentCaptor<V1ConfigMap> replacement = ArgumentCaptor.forClass(V1ConfigMap.class);
        verify(core).replaceNamespacedConfigMap(any(), any(), replacement.capture());
        String rendered = replacement.getValue().getData().get("haproxy.cfg");
        assertFalse(rendered.contains("port_31000"));
        assertFalse(rendered.contains("port_31001"));
        assertFalse(rendered.contains("allowed_31000"));
        assertFalse(rendered.contains("allowed_31001"));
    }

    @Test
    void disabledInstanceCanRemoveMetadataRouteWithoutWaitingForGatewayRollout() {
        DatabaseProperties properties = new DatabaseProperties();
        GatewayReconciliationLock lock = mock(GatewayReconciliationLock.class);
        DatabaseMetadataRepository repository = mock(DatabaseMetadataRepository.class);
        SharedGatewayService service = service(properties, lock, repository);
        DatabaseMetadata database = new DatabaseMetadata();
        database.setDatabaseId("db-123456789012");
        database.setPublicPort(31000);

        service.removeRoute(database);

        verify(repository).save(database);
        verifyNoInteractions(lock);
    }

    @Test
    void releasePortClearsCidrsSoDeletedOrPrivateDatabasesStopContributingSourceRanges() {
        DatabaseProperties properties = new DatabaseProperties();
        DatabaseMetadataRepository repository = mock(DatabaseMetadataRepository.class);
        SharedGatewayService service = service(properties, mock(GatewayReconciliationLock.class), repository);
        DatabaseMetadata database = database(31000);

        service.releasePort(database);

        assertNull(database.getPublicPort());
        assertEquals("[]", database.getAllowedCidrs());
        verify(repository).save(database);
    }

    private SharedGatewayService service(DatabaseProperties properties, GatewayReconciliationLock lock) {
        return service(properties, lock, mock(DatabaseMetadataRepository.class));
    }

    private SharedGatewayService service(DatabaseProperties properties, GatewayReconciliationLock lock,
                                         DatabaseMetadataRepository repository) {
        return new SharedGatewayService(properties, repository, mock(PublicPortAllocator.class),
                mock(KubeBlocksClient.class), new ApiClient(), lock, mock(DatabaseBackendResolver.class));
    }

    private SharedGatewayService service(DatabaseProperties properties, GatewayReconciliationLock lock,
                                         DatabaseMetadataRepository repository, CoreV1Api core,
                                         AppsV1Api apps, KubeBlocksClient kubeBlocksClient,
                                         DatabaseBackendResolver backendResolver) {
        return new SharedGatewayService(properties, repository, mock(PublicPortAllocator.class),
                kubeBlocksClient, core, apps, lock, backendResolver);
    }

    private DatabaseProperties enabledProperties() {
        DatabaseProperties properties = new DatabaseProperties();
        properties.getGateway().setReconcileEnabled(true);
        properties.getGateway().setPortStart(31000);
        properties.getGateway().setPortEnd(31030);
        return properties;
    }

    private GatewayReconciliationLock runningLock() {
        GatewayReconciliationLock lock = mock(GatewayReconciliationLock.class);
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(lock).execute(any(Runnable.class));
        return lock;
    }

    private V1Service gatewayService(int start, int end) {
        List<V1ServicePort> ports = new ArrayList<>();
        for (int port = start; port <= end; port++) {
            ports.add(new V1ServicePort()
                    .name("db-" + port)
                    .port(port)
                    .nodePort(1000 + port)
                    .protocol("TCP"));
        }
        return new V1Service()
                .metadata(new V1ObjectMeta().name("dbaas-public-gateway")
                        .annotations(Map.of("loadbalancer.openstack.org/proxy-protocol", "false")))
                .spec(new V1ServiceSpec()
                        .type("LoadBalancer")
                        .selector(Map.of("app", "haproxy"))
                        .externalTrafficPolicy("Local")
                        .loadBalancerSourceRanges(List.of("49.50.73.146/32"))
                        .ports(ports))
                .status(new V1ServiceStatus().loadBalancer(new V1LoadBalancerStatus()
                        .ingress(List.of(new V1LoadBalancerIngress().ip("203.0.113.10")))));
    }

    private V1ServicePort port(V1Service service, int port) {
        return service.getSpec().getPorts().stream()
                .filter(candidate -> Integer.valueOf(port).equals(candidate.getPort()))
                .findFirst()
                .orElseThrow();
    }

    private V1ConfigMap configMap(String config) {
        return new V1ConfigMap().metadata(new V1ObjectMeta().name("dbaas-public-gateway-config"))
                .data(Map.of("haproxy.cfg", config));
    }

    private V1Deployment deployment() throws Exception {
        return deployment(sha256(renderedEmptyGateway()), 2, 2);
    }

    private V1Deployment deployment(String checksum, int available, int updated) {
        return new V1Deployment()
                .metadata(new V1ObjectMeta().name("dbaas-public-gateway"))
                .spec(new V1DeploymentSpec()
                        .replicas(2)
                        .template(new V1PodTemplateSpec()
                                .metadata(new V1ObjectMeta().annotations(Map.of(
                                        "dbaas.cyfuture.com/config-checksum",
                                        checksum)))))
                .status(new V1DeploymentStatus().availableReplicas(available).updatedReplicas(updated));
    }

    private String renderedEmptyGateway() {
        return """
                global
                  log stdout format raw local0
                  maxconn 10000

                defaults
                  mode tcp
                  log global
                  option tcplog
                  timeout connect 5s
                  timeout client 1h
                  timeout server 1h

                resolvers kubernetes
                  parse-resolv-conf
                  hold valid 10s

                frontend health
                  bind *:8404
                  mode http
                  http-request return status 200 content-type text/plain string ok

                frontend public_databases
                  bind *:31000-31030
                  tcp-request connection reject
                """;
    }

    private DatabaseMetadata database(int publicPort) {
        DatabaseMetadata database = new DatabaseMetadata();
        database.setDatabaseId("db-orders0001");
        database.setProjectName("orders");
        database.setNamespaceName("dbaas-orders");
        database.setEngine(DatabaseEngine.POSTGRESQL);
        database.setMode(DatabaseMode.REPLICATION);
        database.setStatus(DatabaseStatus.RUNNING);
        database.setPublicPort(publicPort);
        database.setAllowedCidrs("[49.50.73.146/32]");
        database.setCreatedAt(Instant.now());
        database.setUpdatedAt(Instant.now());
        return database;
    }

    private DatabaseObservation observation() {
        return observationWithServiceReady(true);
    }

    private DatabaseObservation observationWithServiceReady(boolean serviceReady) {
        return new DatabaseObservation("db-orders0001", "orders-db",
                DatabaseEngine.POSTGRESQL, DatabaseMode.REPLICATION, "17.5.0", SizePlan.C1G1,
                10, false, DatabaseStatus.RUNNING, 2, 1, 1, 0, 0, 0, 2, 2,
                serviceReady, "db-orders0001-postgresql.dbaas-orders.svc.cluster.local", 5432,
                List.of(), serviceReady ? "ready" : "service not ready");
    }

    private String renderedRoute(DatabaseMetadata database, int port) {
        return "global\n  log stdout format raw local0\n  maxconn 10000\n\n"
                + "defaults\n  mode tcp\n  log global\n  option tcplog\n"
                + "  timeout connect 5s\n  timeout client 1h\n  timeout server 1h\n\n"
                + "resolvers kubernetes\n  parse-resolv-conf\n  hold valid 10s\n\n"
                + "frontend health\n  bind *:8404\n  mode http\n"
                + "  http-request return status 200 content-type text/plain string ok\n\n"
                + "frontend public_databases\n  bind *:31000-31030\n"
                + "  acl configured_port dst_port " + port + " \n"
                + "  # route " + database.getDatabaseId() + "\n"
                + "  acl port_" + port + " dst_port " + port + "\n"
                + "  tcp-request content reject if !configured_port\n"
                + "  use_backend database_" + port + " if port_" + port + "\n\n"
                + "backend database_" + port + "\n"
                + "  server database db-orders0001-postgresql.dbaas-orders.svc.cluster.local:5432"
                + " check resolvers kubernetes init-addr libc,none\n\n";
    }

    private String sha256(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private void assertNoMissingAclReferences(String rendered) {
        Set<String> defined = new HashSet<>();
        for (String line : rendered.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.startsWith("acl ")) {
                defined.add(trimmed.split("\\s+")[1]);
            }
        }
        for (String line : rendered.lines().toList()) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("tcp-request content reject if ")
                    && !trimmed.startsWith("use_backend ")) continue;
            for (String token : trimmed.split("\\s+")) {
                String acl = token.startsWith("!") ? token.substring(1) : token;
                if (acl.equals("if") || acl.startsWith("database_")) continue;
                if (acl.equals("configured_port") || acl.startsWith("port_") || acl.startsWith("allowed_")) {
                    assertTrue(defined.contains(acl), "Missing ACL definition for " + acl);
                }
            }
        }
    }
}
