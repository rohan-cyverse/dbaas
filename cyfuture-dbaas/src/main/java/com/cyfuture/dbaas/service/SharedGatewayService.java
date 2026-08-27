package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.config.DatabaseProperties;
import com.cyfuture.dbaas.dto.DatabaseResponse;
import com.cyfuture.dbaas.dto.PublicEndpointResponse;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.exception.ApiException;
import com.cyfuture.dbaas.model.DatabaseStatus;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1LoadBalancerIngress;
import io.kubernetes.client.openapi.models.V1Service;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Manages routes on infrastructure that the platform team installed once. */
@Service
@Slf4j
public class SharedGatewayService {
    private static final String CONFIG_KEY = "haproxy.cfg";
    private static final String CHECKSUM = "dbaas.cyfuture.com/config-checksum";
    private static final String PROXY_PROTOCOL =
            "loadbalancer.openstack.org/proxy-protocol";
    private static final Pattern EXISTING_ROUTE = Pattern.compile(
            "# route\\s+(db-[A-Za-z0-9-]+)\\s*\\R\\s*acl port_(\\d+)");

    private final DatabaseProperties properties;
    private final DatabaseMetadataRepository databaseRepository;
    private final PublicPortAllocator portAllocator;
    private final KubeBlocksClient kubeBlocksClient;
    private final CoreV1Api coreV1Api;
    private final AppsV1Api appsV1Api;

    public SharedGatewayService(DatabaseProperties properties,
                                DatabaseMetadataRepository databaseRepository,
                                PublicPortAllocator portAllocator,
                                KubeBlocksClient kubeBlocksClient,
                                ApiClient apiClient) {
        this.properties = properties;
        this.databaseRepository = databaseRepository;
        this.portAllocator = portAllocator;
        this.kubeBlocksClient = kubeBlocksClient;
        this.coreV1Api = new CoreV1Api(apiClient);
        this.appsV1Api = new AppsV1Api(apiClient);
    }

    public synchronized PublicEndpointResponse configure(DatabaseMetadata database) {
        if (database.getPublicPort() == null) {
            database.setPublicPort(portAllocator.allocate());
            database.setUpdatedAt(Instant.now());
            databaseRepository.save(database);
        }
        reconcileNow();
        return endpoint(database);
    }

    public synchronized void reconcileNow() {
        Infrastructure infrastructure = infrastructure();
        adoptExistingRoutes(infrastructure.configMap());
        assignMissingPorts();
        List<Route> routes = activeRoutes();
        String config = render(routes);
        String checksum = checksum(config);

        try {
            updateSourceRanges(infrastructure.service(), routes);

            V1ConfigMap configMap = infrastructure.configMap();
            String current = configMap.getData() == null
                    ? null : configMap.getData().get(CONFIG_KEY);
            if (!Objects.equals(current, config)) {
                configMap.setData(Map.of(CONFIG_KEY, config));
                coreV1Api.replaceNamespacedConfigMap(
                        settings().getConfigMapName(), settings().getNamespace(), configMap)
                        .execute();

            }

            V1Deployment deployment = infrastructure.deployment();
            Map<String, String> existingAnnotations = deployment.getSpec().getTemplate()
                    .getMetadata().getAnnotations();
            String deployedChecksum = existingAnnotations == null
                    ? null : existingAnnotations.get(CHECKSUM);
            if (!checksum.equals(deployedChecksum)) {
                Map<String, String> annotations = deployment.getSpec().getTemplate()
                        .getMetadata().getAnnotations();
                annotations = annotations == null
                        ? new LinkedHashMap<>() : new LinkedHashMap<>(annotations);
                annotations.put(CHECKSUM, checksum);
                annotations.put("dbaas.cyfuture.com/reconciled-at", Instant.now().toString());
                deployment.getSpec().getTemplate().getMetadata().setAnnotations(annotations);
                appsV1Api.replaceNamespacedDeployment(
                        settings().getDeploymentName(), settings().getNamespace(), deployment)
                        .execute();
            }
        } catch (io.kubernetes.client.openapi.ApiException exception) {
            throw kubernetesError("Could not update shared public gateway", exception);
        }
    }

    public PublicEndpointResponse endpoint(DatabaseMetadata database) {
        int port = database.getPublicPort() == null ? 0 : database.getPublicPort();
        List<String> cidrs = cidrs(database.getAllowedCidrs());
        if (port == 0) return new PublicEndpointResponse(null, 0, false, cidrs);

        try {
            Infrastructure infrastructure = infrastructure();
            String host = externalHost(infrastructure.service());
            String config = infrastructure.configMap().getData() == null ? ""
                    : infrastructure.configMap().getData().getOrDefault(CONFIG_KEY, "");
            boolean declared = infrastructure.service().getSpec().getPorts().stream()
                    .anyMatch(item -> Integer.valueOf(port).equals(item.getPort()));
            boolean configured = config.contains("# route " + database.getDatabaseId());
            boolean ready = host != null && declared && configured
                    && rolloutReady(infrastructure.deployment(), config);
            return new PublicEndpointResponse(host, port, ready, cidrs);
        } catch (ApiException exception) {
            return new PublicEndpointResponse(null, port, false, cidrs);
        }
    }

    public synchronized void removeRouteAndRelease(DatabaseMetadata database) {
        removeRoute(database);
        releasePort(database);
    }

    public synchronized void removeRoute(DatabaseMetadata database) {
        Integer reservedPort = database.getPublicPort();
        if (reservedPort == null) return;
        database.setUpdatedAt(Instant.now());
        databaseRepository.save(database);
        reconcileNow();
        waitForRollout();
    }

    public synchronized void releasePort(DatabaseMetadata database) {
        Integer reservedPort = database.getPublicPort();
        if (reservedPort == null) return;
        database.setPublicPort(null);
        database.setUpdatedAt(Instant.now());
        databaseRepository.save(database);
    }

    @Scheduled(fixedDelayString = "${dbaas.gateway.reconcile-ms:10000}")
    public void scheduledReconcile() {
        try {
            reconcileNow();
        } catch (Exception exception) {
            log.warn("Shared gateway reconciliation will retry: {}", exception.getMessage());
        }
    }

    private List<Route> activeRoutes() {
        List<Route> routes = new ArrayList<>();
        for (DatabaseMetadata database : databaseRepository
                .findByPublicPortIsNotNullOrderByPublicPortAsc()) {
            if (database.getStatus() == DatabaseStatus.DELETING
                    || database.getStatus() == DatabaseStatus.DELETED
                    || database.getStatus() == DatabaseStatus.MISSING
                    || database.getStatus() == DatabaseStatus.ORPHANED
                    || database.getStatus() == DatabaseStatus.FAILED) continue;
            List<String> allowed = cidrs(database.getAllowedCidrs());
            if (allowed.isEmpty()) continue;
            try {
                DatabaseResponse live = kubeBlocksClient.get(
                        database.getNamespaceName(), database.getDatabaseId());
                if (live.privateEndpoint() != null && live.privateEndpoint().ready()) {
                    routes.add(new Route(database.getDatabaseId(), database.getPublicPort(),
                            live.privateEndpoint().host(), live.privateEndpoint().port(), allowed));
                }
            } catch (Exception ignored) {
                // The scheduled reconciler retries while KubeBlocks creates the Service.
            }
        }
        routes.sort(Comparator.comparingInt(Route::publicPort));
        return routes;
    }

    private void adoptExistingRoutes(V1ConfigMap configMap) {
        String config = configMap.getData() == null ? ""
                : configMap.getData().getOrDefault(CONFIG_KEY, "");
        Matcher matcher = EXISTING_ROUTE.matcher(config);
        while (matcher.find()) {
            String databaseId = matcher.group(1);
            int port = Integer.parseInt(matcher.group(2));
            if (port < settings().getPortStart() || port > settings().getPortEnd()) continue;
            databaseRepository.findById(databaseId).ifPresent(database -> {
                if (database.getPublicPort() == null
                        && !databaseRepository.existsByPublicPort(port)) {
                    database.setPublicPort(port);
                    database.setUpdatedAt(Instant.now());
                    databaseRepository.save(database);
                }
            });
        }
    }

    private void assignMissingPorts() {
        for (DatabaseMetadata database : databaseRepository.findAllByOrderByCreatedAtAsc()) {
            if (database.getPublicPort() != null
                    || database.getStatus() == DatabaseStatus.DELETING
                    || database.getStatus() == DatabaseStatus.DELETED
                    || database.getStatus() == DatabaseStatus.MISSING
                    || database.getStatus() == DatabaseStatus.ORPHANED
                    || database.getStatus() == DatabaseStatus.FAILED) continue;
            try {
                DatabaseResponse live = kubeBlocksClient.get(
                        database.getNamespaceName(), database.getDatabaseId());
                if (live.status() == DatabaseStatus.FAILED) continue;
                database.setPublicPort(portAllocator.allocate());
                database.setUpdatedAt(Instant.now());
                databaseRepository.save(database);
            } catch (ApiException exception) {
                log.debug("Shared-gateway port for {} will be assigned later: {}",
                        database.getDatabaseId(), exception.getMessage());
            }
        }
    }

    private Infrastructure infrastructure() {
        try {
            V1Service service = coreV1Api.readNamespacedService(
                    settings().getServiceName(), settings().getNamespace()).execute();
            V1ConfigMap configMap = coreV1Api.readNamespacedConfigMap(
                    settings().getConfigMapName(), settings().getNamespace()).execute();
            V1Deployment deployment = appsV1Api.readNamespacedDeployment(
                    settings().getDeploymentName(), settings().getNamespace()).execute();
            verify(service);
            return new Infrastructure(service, configMap, deployment);
        } catch (io.kubernetes.client.openapi.ApiException exception) {
            throw kubernetesError(
                    "Shared gateway infrastructure is missing; install it once on the cluster",
                    exception);
        }
    }

    private void verify(V1Service service) {
        if (service.getSpec() == null || service.getSpec().getPorts() == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Shared gateway Service has no listeners");
        }
        Set<Integer> actual = new LinkedHashSet<>();
        service.getSpec().getPorts().forEach(port -> actual.add(port.getPort()));
        for (int port = settings().getPortStart(); port <= settings().getPortEnd(); port++) {
            if (!actual.contains(port)) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Shared gateway Service is missing pre-created port " + port);
            }
        }
        Map<String, String> annotations = service.getMetadata().getAnnotations();
        String proxy = annotations == null ? null : annotations.get(PROXY_PROTOCOL);
        if (!"true".equalsIgnoreCase(proxy) && !"v2".equalsIgnoreCase(proxy)) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Shared gateway OpenStack PROXY protocol is not enabled");
        }
        if (externalHost(service) == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Shared gateway is waiting for its permanent public IP");
        }
    }

    private void updateSourceRanges(V1Service service, List<Route> routes)
            throws io.kubernetes.client.openapi.ApiException {
        List<String> desired = routes.stream().flatMap(route -> route.allowedCidrs().stream())
                .distinct().sorted().toList();
        List<String> current = service.getSpec().getLoadBalancerSourceRanges() == null
                ? List.of() : service.getSpec().getLoadBalancerSourceRanges().stream()
                .sorted().toList();
        if (!desired.isEmpty() && !desired.equals(current)) {
            service.getSpec().setLoadBalancerSourceRanges(desired);
            coreV1Api.replaceNamespacedService(
                    settings().getServiceName(), settings().getNamespace(), service).execute();
        }
    }

    private String render(List<Route> routes) {
        StringBuilder value = new StringBuilder()
                .append("global\n  log stdout format raw local0\n  maxconn 10000\n\n")
                .append("defaults\n  mode tcp\n  log global\n  option tcplog\n")
                .append("  timeout connect 5s\n  timeout client 1h\n  timeout server 1h\n\n")
                .append("resolvers kubernetes\n  parse-resolv-conf\n  hold valid 10s\n\n")
                .append("frontend health\n  bind *:8404\n  mode http\n")
                .append("  http-request return status 200 content-type text/plain string ok\n\n")
                .append("frontend public_databases\n  bind *:")
                .append(settings().getPortStart()).append("-")
                .append(settings().getPortEnd()).append(" accept-proxy\n");
        if (routes.isEmpty()) return value.append("  tcp-request connection reject\n").toString();

        value.append("  acl configured_port dst_port ");
        routes.forEach(route -> value.append(route.publicPort()).append(" "));
        value.append("\n  tcp-request connection reject if !configured_port\n");
        routes.forEach(route -> value.append("  # route ").append(route.databaseId()).append("\n")
                .append("  acl port_").append(route.publicPort()).append(" dst_port ")
                .append(route.publicPort()).append("\n"));
        routes.forEach(route -> value.append("  use_backend database_")
                .append(route.publicPort()).append(" if port_")
                .append(route.publicPort()).append("\n"));
        value.append("\n");
        routes.forEach(route -> value.append("backend database_").append(route.publicPort())
                .append("\n  server database ").append(route.host()).append(":")
                .append(route.targetPort())
                .append(" check resolvers kubernetes init-addr libc,none\n\n"));
        return value.toString();
    }

    private boolean rolloutReady(V1Deployment deployment, String config) {
        String deployed = deployment.getSpec().getTemplate().getMetadata().getAnnotations() == null
                ? null : deployment.getSpec().getTemplate().getMetadata().getAnnotations().get(CHECKSUM);
        int desired = deployment.getSpec().getReplicas() == null ? 1 : deployment.getSpec().getReplicas();
        int available = deployment.getStatus() == null
                || deployment.getStatus().getAvailableReplicas() == null
                ? 0 : deployment.getStatus().getAvailableReplicas();
        int updated = deployment.getStatus() == null
                || deployment.getStatus().getUpdatedReplicas() == null
                ? 0 : deployment.getStatus().getUpdatedReplicas();
        return checksum(config).equals(deployed) && available >= desired && updated >= desired;
    }

    private void waitForRollout() {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                Infrastructure current = infrastructure();
                String config = current.configMap().getData() == null ? ""
                        : current.configMap().getData().getOrDefault(CONFIG_KEY, "");
                if (rolloutReady(current.deployment(), config)) return;
                Thread.sleep(1000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "Shared gateway rollout did not complete; public port remains reserved");
    }

    private String externalHost(V1Service service) {
        if (service.getStatus() == null || service.getStatus().getLoadBalancer() == null
                || service.getStatus().getLoadBalancer().getIngress() == null
                || service.getStatus().getLoadBalancer().getIngress().isEmpty()) return null;
        V1LoadBalancerIngress ingress = service.getStatus().getLoadBalancer().getIngress().get(0);
        return ingress.getIp() == null ? ingress.getHostname() : ingress.getIp();
    }

    private List<String> cidrs(String stored) {
        if (stored == null || stored.isBlank() || "[]".equals(stored.trim())) return List.of();
        String value = stored.trim();
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        if (value.isBlank()) return List.of();
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim).filter(item -> !item.isBlank()).toList();
    }

    private String checksum(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not checksum HAProxy configuration", exception);
        }
    }

    private DatabaseProperties.GatewaySettings settings() {
        return properties.getGateway();
    }

    private ApiException kubernetesError(String message,
                                         io.kubernetes.client.openapi.ApiException exception) {
        String detail = exception.getResponseBody();
        return new ApiException(HttpStatus.BAD_GATEWAY,
                message + ": " + (detail == null || detail.isBlank()
                        ? exception.getMessage() : detail));
    }

    private record Route(String databaseId, int publicPort, String host,
                         int targetPort, List<String> allowedCidrs) {}
    private record Infrastructure(V1Service service, V1ConfigMap configMap,
                                  V1Deployment deployment) {}
}
