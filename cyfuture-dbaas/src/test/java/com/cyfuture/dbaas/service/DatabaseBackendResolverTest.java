package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.model.DatabaseEngine;
import com.cyfuture.dbaas.model.DatabaseMode;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.openapi.models.V1ServicePort;
import io.kubernetes.client.openapi.models.V1ServiceSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import org.mockito.ArgumentCaptor;

class DatabaseBackendResolverTest {
    @Test
    void createsStableServiceWhoseSelectorTracksMongoPrimary() throws Exception {
        DatabaseMetadata database = database(DatabaseEngine.MONGODB, DatabaseMode.REPLICA_SET);
        database.setNamespaceName("dbaas-orders");
        CoreV1Api api = mock(CoreV1Api.class, RETURNS_DEEP_STUBS);
        V1Service base = service("db-orders-mongodb", Map.of("app", "db-orders"),
                Map.of("app.kubernetes.io/instance", "db-orders"));
        when(api.readNamespacedService("db-orders-mongodb-primary", "dbaas-orders").execute())
                .thenThrow(new io.kubernetes.client.openapi.ApiException(404, "missing"));
        when(api.listNamespacedService("dbaas-orders").execute())
                .thenReturn(new io.kubernetes.client.openapi.models.V1ServiceList().items(List.of(base)));
        V1Service created = service("db-orders-mongodb-primary", Map.of(),
                Map.of("app.kubernetes.io/instance", "db-orders", "kubeblocks.io/role", "primary"));
        when(api.createNamespacedService(eq("dbaas-orders"), any()).execute()).thenReturn(created);

        V1Service selected = DatabaseBackendResolver.ensureMongoPrimaryService(api, database, 27017);

        assertEquals("db-orders-mongodb-primary", selected.getMetadata().getName());
        ArgumentCaptor<V1Service> requested = ArgumentCaptor.forClass(V1Service.class);
        verify(api).createNamespacedService(eq("dbaas-orders"), requested.capture());
        assertEquals("db-orders", requested.getValue().getSpec().getSelector()
                .get("app.kubernetes.io/instance"));
        assertEquals("primary", requested.getValue().getSpec().getSelector().get("kubeblocks.io/role"));
    }

    @Test
    void mongoReplicaSetUsesRoleAwarePrimaryService() {
        DatabaseMetadata database = database(DatabaseEngine.MONGODB, DatabaseMode.REPLICA_SET);

        V1Service generic = service("db-orders-mongodb", Map.of("app", "db-orders"), Map.of());
        V1Service primary = service("db-orders-mongodb-replicaset-primary", Map.of("app", "db-orders"),
                Map.of("kubeblocks.io/role", "primary"));

        V1Service selected = DatabaseBackendResolver.select(List.of(generic, primary), database, 27017)
                .orElseThrow();

        assertEquals("db-orders-mongodb-replicaset-primary", selected.getMetadata().getName());
    }

    @Test
    void genericMongoServiceCanBeUsedAsTemplateForPrimaryService() {
        DatabaseMetadata database = database(DatabaseEngine.MONGODB, DatabaseMode.REPLICA_SET);
        V1Service generic = service("db-orders-mongodb", Map.of("app", "db-orders"), Map.of());

        assertTrue(DatabaseBackendResolver.select(List.of(generic), database, 27017).isPresent());
    }

    @Test
    void shardedMongoAlwaysUsesAMongosRouter() {
        DatabaseMetadata database = database(DatabaseEngine.MONGODB, DatabaseMode.SHARDING);
        V1Service configServer = service("db-orders-config-server", Map.of(), Map.of());
        V1Service shard = service("db-orders-shard-a1b", Map.of(), Map.of());
        V1Service mongos = service("db-orders-mongos-mongos-0", Map.of(), Map.of());

        V1Service selected = DatabaseBackendResolver.select(
                List.of(configServer, shard, mongos), database, 27017).orElseThrow();

        assertEquals("db-orders-mongos-mongos-0", selected.getMetadata().getName());
    }

    @Test
    void resolverNeverSelectsAServiceFromAnotherDatabase() {
        DatabaseMetadata database = database(DatabaseEngine.POSTGRESQL, DatabaseMode.REPLICATION);
        V1Service other = service("db-another-postgresql", Map.of("app", "db-another"), Map.of());
        V1Service expected = service("db-orders-postgresql", Map.of("app", "db-orders"), Map.of());

        V1Service selected = DatabaseBackendResolver.select(List.of(other, expected), database, 27017)
                .orElseThrow();

        assertEquals("db-orders-postgresql", selected.getMetadata().getName());
    }

    private DatabaseMetadata database(DatabaseEngine engine, DatabaseMode mode) {
        DatabaseMetadata database = new DatabaseMetadata();
        database.setDatabaseId("db-orders");
        database.setEngine(engine);
        database.setMode(mode);
        return database;
    }

    private V1Service service(String name, Map<String, String> labels, Map<String, String> selector) {
        return new V1Service()
                .metadata(new V1ObjectMeta().name(name).labels(labels))
                .spec(new V1ServiceSpec().clusterIP("10.0.0.1").selector(selector)
                        .ports(List.of(new V1ServicePort().port(27017))));
    }

}
