package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.model.DatabaseEngine;
import com.cyfuture.dbaas.model.DatabaseMode;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.*;
import org.springframework.stereotype.Component;

import java.util.*;

/** Resolves stable, role-aware Kubernetes Services; it never resolves Pods. */
@Component
public class DatabaseBackendResolver {
    static final String ROLE_LABEL = "kubeblocks.io/role";
    private final CoreV1Api api;

    public DatabaseBackendResolver(io.kubernetes.client.openapi.ApiClient client) { this.api = new CoreV1Api(client); }
    public DatabaseBackendEndpoint resolve(DatabaseMetadata database) { return resolve(database, EndpointRole.READ_WRITE); }

    public DatabaseBackendEndpoint resolve(DatabaseMetadata database, EndpointRole role) {
        int port = databasePort(database.getEngine());
        try {
            if (role == EndpointRole.READ_ONLY && !supportsReadOnly(database))
                throw new IllegalStateException("No read replicas are configured for " + database.getDatabaseId());
            V1Service service = database.getMode() == DatabaseMode.STANDALONE
                    || database.getMode() == DatabaseMode.SHARDING
                    ? select(api.listNamespacedService(database.getNamespaceName()).execute().getItems(), database, port)
                    .orElseThrow(() -> new IllegalStateException("No client Service found for " + database.getDatabaseId()))
                    : ensureRoleService(api, database, port, role, database.physicalClusterName());
            String name = service.getMetadata().getName();
            return new DatabaseBackendEndpoint(name, database.getNamespaceName(),
                    name + "." + database.getNamespaceName() + ".svc.cluster.local", port);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not resolve " + role + " Service for "
                    + database.getDatabaseId(), exception);
        }
    }

    public boolean hasReadyEndpoints(DatabaseBackendEndpoint endpoint) {
        try {
            V1Endpoints endpoints = api.readNamespacedEndpoints(endpoint.serviceName(), endpoint.namespace()).execute();
            return endpoints.getSubsets() != null && endpoints.getSubsets().stream()
                    .filter(Objects::nonNull)
                    .anyMatch(subset -> subset.getAddresses() != null && !subset.getAddresses().isEmpty());
        } catch (Exception exception) { return false; }
    }

    public void deleteOwnedServices(DatabaseMetadata database) {
        for (EndpointRole role : EndpointRole.values()) {
            String name = serviceName(database, role);
            try { api.deleteNamespacedService(name, database.getNamespaceName()).execute(); }
            catch (ApiException exception) {
                if (exception.getCode() != 404) throw new IllegalStateException("Could not delete Service " + name, exception);
            }
        }
    }

    static boolean supportsReadOnly(DatabaseMetadata database) {
        return database.getMode() != DatabaseMode.STANDALONE
                && database.getMode() != DatabaseMode.SHARDING && database.getReplicas() > 1;
    }

    static Optional<V1Service> select(List<V1Service> services, DatabaseMetadata db, int port) {
        return select(services, db, port, db.physicalClusterName());
    }

    static Optional<V1Service> select(List<V1Service> services, DatabaseMetadata db, int port, String cluster) {
        return services.stream()
                .filter(s -> s.getMetadata() != null && s.getMetadata().getName() != null)
                .filter(s -> belongsToDatabase(s, cluster))
                .filter(s -> s.getSpec() != null && s.getSpec().getClusterIP() != null
                        && !"None".equalsIgnoreCase(s.getSpec().getClusterIP()))
                .filter(s -> s.getSpec().getPorts() != null && s.getSpec().getPorts().stream()
                        .anyMatch(p -> p.getPort() != null && p.getPort() == port))
                .filter(s -> !isReadOnly(s))
                .min(Comparator.comparingInt((V1Service s) -> servicePriority(s, db))
                        .thenComparing(s -> s.getMetadata().getName()));
    }

    static V1Service ensureMongoPrimaryService(CoreV1Api api, DatabaseMetadata db, int port) throws ApiException {
        return ensureMongoPrimaryService(api, db, port, db.physicalClusterName());
    }
    static V1Service ensureMongoPrimaryService(CoreV1Api api, DatabaseMetadata db, int port,
                                               String cluster) throws ApiException {
        return ensureRoleService(api, db, port, EndpointRole.READ_WRITE, cluster);
    }

    static V1Service ensureRoleService(CoreV1Api api, DatabaseMetadata db, int port,
                                       EndpointRole role, String cluster) throws ApiException {
        List<V1Service> services = api.listNamespacedService(db.getNamespaceName()).execute().getItems();
        // KubeBlocks already creates stable primary/secondary Services for
        // replicated engines. Prefer those operator-owned resources so the
        // gateway never points at an unnecessary DBaaS duplicate which may
        // be absent after restart, restore or operator reconciliation.
        Optional<V1Service> operatorRoleService = services.stream()
                .filter(service -> service.getMetadata() != null
                        && service.getMetadata().getName() != null)
                .filter(service -> belongsToDatabase(service, cluster))
                .filter(service -> service.getSpec() != null
                        && service.getSpec().getClusterIP() != null
                        && !"None".equalsIgnoreCase(service.getSpec().getClusterIP()))
                .filter(service -> service.getSpec().getPorts() != null
                        && service.getSpec().getPorts().stream()
                        .anyMatch(servicePort -> servicePort.getPort() != null
                                && servicePort.getPort() == port))
                .filter(service -> hasExactRole(service.getSpec().getSelector(), role.roleValue))
                .min(Comparator.comparing(service -> service.getMetadata().getName()));
        if (operatorRoleService.isPresent()) return operatorRoleService.get();

        String name = serviceName(db, role);
        V1Service template = select(services, db, port, cluster)
                .filter(s -> !s.getMetadata().getName().equals(name))
                .orElseThrow(() -> new IllegalStateException("KubeBlocks client Service is not ready for " + db.getDatabaseId()));
        if (template.getSpec().getSelector() == null || template.getSpec().getSelector().isEmpty())
            throw new IllegalStateException("KubeBlocks client Service has no pod selector for " + db.getDatabaseId());
        Map<String, String> selector = new LinkedHashMap<>(template.getSpec().getSelector());
        selector.keySet().removeIf(key -> key.equals("apps.kubeblocks.io/role") || key.equals(ROLE_LABEL));
        selector.put(ROLE_LABEL, role.roleValue);
        try {
            V1Service existing = api.readNamespacedService(name, db.getNamespaceName()).execute();
            if (selector.equals(existing.getSpec().getSelector())) return existing;
            existing.getSpec().setSelector(selector);
            existing.getSpec().setPorts(template.getSpec().getPorts());
            return api.replaceNamespacedService(name, db.getNamespaceName(), existing).execute();
        } catch (ApiException exception) { if (exception.getCode() != 404) throw exception; }
        V1Service service = new V1Service().apiVersion("v1").kind("Service")
                .metadata(new V1ObjectMeta().name(name).namespace(db.getNamespaceName()).labels(Map.of(
                        "app.kubernetes.io/managed-by", "cyfuture-dbaas",
                        "dbaas.cyfuture.com/database-id", db.getDatabaseId(),
                        "dbaas.cyfuture.com/endpoint-role", role.name().toLowerCase())))
                .spec(new V1ServiceSpec().selector(selector).ports(template.getSpec().getPorts()));
        return api.createNamespacedService(db.getNamespaceName(), service).execute();
    }

    private static String serviceName(DatabaseMetadata db, EndpointRole role) {
        if (db.getEngine() == DatabaseEngine.MONGODB && role == EndpointRole.READ_WRITE)
            return db.getDatabaseId() + "-mongodb-primary";
        return db.getDatabaseId() + (role == EndpointRole.READ_WRITE ? "-rw" : "-ro");
    }
    private static int databasePort(DatabaseEngine engine) {
        return switch (engine) { case POSTGRESQL -> 5432; case MYSQL -> 3306; case MONGODB -> 27017; };
    }
    private static int servicePriority(V1Service service, DatabaseMetadata db) {
        if (db.getEngine() == DatabaseEngine.MONGODB && db.getMode() == DatabaseMode.SHARDING) {
            String name = service.getMetadata().getName();
            if (name.matches(java.util.regex.Pattern.quote(db.getDatabaseId()) + "-mongos-mongos-[0-9]+")) return 0;
            if (name.contains("-mongos")) return 1;
            return 10;
        }
        return isStrictPrimaryService(service) ? 0 : 1;
    }
    private static boolean belongsToDatabase(V1Service s, String id) {
        String name = s.getMetadata().getName();
        return name.equals(id) || name.startsWith(id + "-") || containsValue(s.getMetadata().getLabels(), id)
                || containsValue(s.getSpec().getSelector(), id);
    }
    private static boolean isReadOnly(V1Service s) {
        String name = s.getMetadata().getName().toLowerCase();
        return name.endsWith("-ro") || name.contains("monitor") || name.contains("readonly")
                || name.contains("read-only") || name.contains("secondary");
    }
    private static boolean isStrictPrimaryService(V1Service s) {
        return s.getSpec() != null && containsRole(s.getSpec().getSelector(), "primary", "leader", "writer");
    }
    private static boolean containsValue(Map<String, String> values, String expected) {
        return values != null && values.values().stream().anyMatch(expected::equals);
    }
    private static boolean containsRole(Map<String, String> values, String... roles) {
        if (values == null) return false;
        return values.entrySet().stream().anyMatch(e -> {
            String key = e.getKey().toLowerCase(), value = e.getValue().toLowerCase();
            return Arrays.stream(roles).anyMatch(role -> (key.contains("role") && value.equals(role)) || value.contains(role));
        });
    }

    private static boolean hasExactRole(Map<String, String> selector, String role) {
        if (selector == null) return false;
        return selector.entrySet().stream().anyMatch(entry ->
                (entry.getKey().equals(ROLE_LABEL)
                        || entry.getKey().equals("apps.kubeblocks.io/role"))
                        && role.equalsIgnoreCase(entry.getValue()));
    }

    public enum EndpointRole { READ_WRITE("primary"), READ_ONLY("secondary");
        private final String roleValue; EndpointRole(String roleValue) { this.roleValue = roleValue; } }
    public record DatabaseBackendEndpoint(String serviceName, String namespace, String host, int port) {}
}
