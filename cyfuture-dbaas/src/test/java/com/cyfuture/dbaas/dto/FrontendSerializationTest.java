package com.cyfuture.dbaas.dto;

import com.cyfuture.dbaas.model.DatabaseEngine;
import com.cyfuture.dbaas.model.DatabaseMode;
import com.cyfuture.dbaas.model.DatabaseStatus;
import com.cyfuture.dbaas.model.SizePlan;
import com.cyfuture.dbaas.model.ProvisioningStage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendSerializationTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void databaseStatusJsonDoesNotExposePrivateEndpoint() throws Exception {
        DatabaseResponse response = new DatabaseResponse("db-1", "orders",
                "dbaas-orders", "orders-db", DatabaseEngine.POSTGRESQL,
                DatabaseMode.STANDALONE, "17.5.0", SizePlan.C1G1, 10,
                true, DatabaseStatus.RUNNING, ProvisioningStage.READY, 100,
                1, 1, 1, true,
                new PrivateEndpointResponse("db-1-postgresql.dbaas-orders.svc.cluster.local", 5432, true),
                new PublicEndpointResponse("203.0.113.10", 31000, true, List.of("203.0.113.7/32")),
                "ready");

        String json = objectMapper.writeValueAsString(response);

        assertFalse(json.contains("privateEndpoint"));
        assertFalse(json.contains("namespace"));
        assertFalse(json.contains("readyReplicas"));
        assertFalse(json.contains("readyVolumes"));
        assertFalse(json.contains("serviceReady"));
        assertFalse(json.contains("allowedCidrs"));
        assertTrue(json.contains("publicEndpoint"));
    }

    @Test
    void connectionJsonDoesNotExposePrivateEndpointOrUri() throws Exception {
        ConnectionResponse response = new ConnectionResponse("db-1", DatabaseEngine.POSTGRESQL,
                "appdb", "dbaas_user", "secret", "postgresql://private",
                "postgresql://public",
                new PrivateEndpointResponse("db-1-postgresql.dbaas-orders.svc.cluster.local", 5432, true),
                new PublicEndpointResponse("203.0.113.10", 31000, true, List.of("203.0.113.7/32")));

        String json = objectMapper.writeValueAsString(response);

        assertFalse(json.contains("privateEndpoint"));
        assertFalse(json.contains("privateConnectionUri"));
        assertFalse(json.contains("allowedCidrs"));
        assertTrue(json.contains("publicConnectionUri"));
    }
}
