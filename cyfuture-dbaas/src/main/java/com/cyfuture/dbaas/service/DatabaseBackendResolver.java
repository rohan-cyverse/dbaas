package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.entity.DatabaseMetadata;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Service;
import org.springframework.stereotype.Component;
import java.util.Comparator;

@Component
public class DatabaseBackendResolver {
    private final CoreV1Api api;
    public DatabaseBackendResolver(io.kubernetes.client.openapi.ApiClient client) { this.api = new CoreV1Api(client); }
    public DatabaseBackendEndpoint resolve(DatabaseMetadata db) {
        int port = switch (db.getEngine()) { case POSTGRESQL -> 5432; case MYSQL -> 3306; case MONGODB -> 27017; };
        try {
            V1Service s = api.listNamespacedService(db.getNamespaceName()).execute().getItems().stream()
                    .filter(x -> x.getMetadata() != null && x.getMetadata().getName() != null)
                    .filter(x -> x.getSpec() != null && x.getSpec().getClusterIP() != null && !"None".equalsIgnoreCase(x.getSpec().getClusterIP()))
                    .filter(x -> x.getSpec().getPorts() != null && x.getSpec().getPorts().stream().anyMatch(p -> p.getPort() != null && p.getPort() == port))
                    .filter(x -> { String n = x.getMetadata().getName().toLowerCase(); return !n.contains("monitor") && !n.contains("readonly") && !n.contains("read-only"); })
                    .min(Comparator.comparing(x -> x.getMetadata().getName()))
                    .orElseThrow(() -> new IllegalStateException("No client Service found for " + db.getDatabaseId()));
            String name = s.getMetadata().getName();
            return new DatabaseBackendEndpoint(name, db.getNamespaceName(), name + "." + db.getNamespaceName() + ".svc.cluster.local", port);
        } catch (Exception e) { throw new IllegalStateException("Could not resolve client Service for " + db.getDatabaseId(), e); }
    }
    public record DatabaseBackendEndpoint(String serviceName, String namespace, String host, int port) {}
}
