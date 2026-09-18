package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.model.DatabaseEngine;
import com.cyfuture.dbaas.model.DatabaseMode;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.openapi.models.V1ServiceSpec;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Comparator;

@Component
public class DatabaseBackendResolver {
    private final CoreV1Api api;
    public DatabaseBackendResolver(io.kubernetes.client.openapi.ApiClient client) { this.api = new CoreV1Api(client); }
    public DatabaseBackendEndpoint resolve(DatabaseMetadata db) {
        int port = switch (db.getEngine()) { case POSTGRESQL -> 5432; case MYSQL -> 3306; case MONGODB -> 27017; };
        try {
            V1Service s = db.getEngine() == DatabaseEngine.MONGODB
                    && db.getMode() == DatabaseMode.REPLICA_SET
                    ? ensureMongoPrimaryService(api, db, port)
                    : select(api.listNamespacedService(db.getNamespaceName()).execute().getItems(), db, port)
                    .orElseThrow(() -> new IllegalStateException("No client Service found for " + db.getDatabaseId()));
            String name = s.getMetadata().getName();
            return new DatabaseBackendEndpoint(name, db.getNamespaceName(), name + "." + db.getNamespaceName() + ".svc.cluster.local", port);
        } catch (Exception e) { throw new IllegalStateException("Could not resolve client Service for " + db.getDatabaseId(), e); }
    }

    static Optional<V1Service> select(List<V1Service> services, DatabaseMetadata db, int port) {
        String physicalClusterName = db.physicalClusterName();
        return services.stream()
                .filter(x -> x.getMetadata() != null && x.getMetadata().getName() != null)
                .filter(x -> belongsToDatabase(x, physicalClusterName))
                .filter(x -> x.getSpec() != null && x.getSpec().getClusterIP() != null
                        && !"None".equalsIgnoreCase(x.getSpec().getClusterIP()))
                .filter(x -> x.getSpec().getPorts() != null && x.getSpec().getPorts().stream()
                        .anyMatch(p -> p.getPort() != null && p.getPort() == port))
                .filter(x -> !isReadOnly(x))
                .min(Comparator
                        .comparingInt((V1Service service) -> servicePriority(service, db))
                        .thenComparing(service -> service.getMetadata().getName()));
    }

    private static int servicePriority(V1Service service, DatabaseMetadata db) {
        if (db.getEngine() == DatabaseEngine.MONGODB
                && db.getMode() == DatabaseMode.SHARDING) {
            String name = service.getMetadata().getName();
            // KubeBlocks exposes application traffic through per-pod mongos
            // Services. Never let a lexicographically earlier config-server
            // or shard Service become the public gateway backend.
            if (name.matches(java.util.regex.Pattern.quote(db.getDatabaseId())
                    + "-mongos-mongos-[0-9]+")) return 0;
            if (name.contains("-mongos")) return 1;
            return 10;
        }
        if (db.getMode() == DatabaseMode.REPLICA_SET && isStrictPrimaryService(service)) {
            return 0;
        }
        return 1;
    }

    static V1Service ensureMongoPrimaryService(CoreV1Api api, DatabaseMetadata db, int port)
            throws io.kubernetes.client.openapi.ApiException {
        String name = db.getDatabaseId() + "-mongodb-primary";
        try {
            V1Service existing = api.readNamespacedService(name, db.getNamespaceName()).execute();
            if (isStrictPrimaryService(existing)) return existing;
            Map<String, String> corrected = existing.getSpec().getSelector() == null
                    ? new java.util.LinkedHashMap<>()
                    : new java.util.LinkedHashMap<>(existing.getSpec().getSelector());
            corrected.put("kubeblocks.io/role", "primary");
            existing.getSpec().setSelector(corrected);
            return api.replaceNamespacedService(name, db.getNamespaceName(), existing).execute();
        } catch (io.kubernetes.client.openapi.ApiException exception) {
            if (exception.getCode() != 404) throw exception;
        }

        V1Service base = select(api.listNamespacedService(db.getNamespaceName()).execute().getItems(), db, port)
                .filter(service -> !service.getMetadata().getName().equals(name))
                .orElseThrow(() -> new IllegalStateException(
                        "MongoDB client Service is not ready for " + db.getDatabaseId()));
        if (base.getSpec().getSelector() == null || base.getSpec().getSelector().isEmpty()) {
            throw new IllegalStateException("MongoDB client Service has no pod selector for "
                    + db.getDatabaseId());
        }
        Map<String, String> selector = new java.util.LinkedHashMap<>(base.getSpec().getSelector());
        selector.put("kubeblocks.io/role", "primary");
        V1Service primary = new V1Service()
                .apiVersion("v1")
                .kind("Service")
                .metadata(new V1ObjectMeta().name(name).namespace(db.getNamespaceName())
                        .labels(Map.of(
                                "app.kubernetes.io/managed-by", "cyfuture-dbaas",
                                "dbaas.cyfuture.com/database-id", db.getDatabaseId())))
                .spec(new V1ServiceSpec().selector(selector).ports(base.getSpec().getPorts()));
        return api.createNamespacedService(db.getNamespaceName(), primary).execute();
    }

    private static boolean belongsToDatabase(V1Service service, String databaseId) {
        String name = service.getMetadata().getName();
        if (name.equals(databaseId) || name.startsWith(databaseId + "-")) return true;
        return containsValue(service.getMetadata().getLabels(), databaseId)
                || containsValue(service.getSpec().getSelector(), databaseId);
    }

    private static boolean isReadOnly(V1Service service) {
        String name = service.getMetadata().getName().toLowerCase();
        return name.contains("monitor") || name.contains("readonly") || name.contains("read-only")
                || name.contains("secondary");
    }

    private static boolean isStrictPrimaryService(V1Service service) {
        return service.getSpec() != null
                && containsRole(service.getSpec().getSelector(), "primary", "leader", "writer");
    }

    private static boolean containsValue(Map<String, String> values, String expected) {
        return values != null && values.values().stream().anyMatch(expected::equals);
    }

    private static boolean containsRole(Map<String, String> values, String... roles) {
        if (values == null) return false;
        return values.entrySet().stream().anyMatch(entry -> {
            String key = entry.getKey().toLowerCase();
            String value = entry.getValue().toLowerCase();
            for (String role : roles) {
                if ((key.contains("role") && value.equals(role)) || value.contains(role)) return true;
            }
            return false;
        });
    }
    public record DatabaseBackendEndpoint(String serviceName, String namespace, String host, int port) {}
}
