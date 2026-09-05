package com.cyfuture.dbaas.dto;

import com.cyfuture.dbaas.model.DatabaseEngine;
import com.cyfuture.dbaas.model.DatabaseMode;
import com.cyfuture.dbaas.model.DatabaseStatus;
import com.cyfuture.dbaas.model.OperationStatus;
import com.cyfuture.dbaas.model.OperationType;
import com.cyfuture.dbaas.model.ProvisioningStage;
import com.cyfuture.dbaas.model.ResourceStatus;
import com.cyfuture.dbaas.model.SizePlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicResponseContractTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void databaseResponseDoesNotExposeKubernetesIdentityOrPrivateRouting() throws Exception {
        DatabaseResponse response = new DatabaseResponse(
                "db-123456789012", "orders", DatabaseEngine.POSTGRESQL,
                DatabaseMode.REPLICATION, "17.5.0", SizePlan.C1G2, 20, 2, 0,
                true, DatabaseStatus.RUNNING, ProvisioningStage.READY, 100,
                new PublicEndpointResponse("203.0.113.10", 31001, true,
                        List.of("203.0.113.4/32")),
                "Database is ready.");

        String json = objectMapper.writeValueAsString(response);

        assertTrue(json.contains("203.0.113.10"));
        assertFalse(json.contains("namespace"));
        assertFalse(json.contains("clusterIP"));
        assertFalse(json.contains("svc.cluster.local"));
        assertFalse(json.contains("privateEndpoint"));
        assertFalse(json.contains("readyReplicas"));
    }

    @Test
    void projectAndOperationResponsesDoNotRepeatRouteScope() throws Exception {
        String projectJson = objectMapper.writeValueAsString(new ProjectResponse(
                "prj-123456789012", "org-000000000000", "Orders", "Production databases",
                ResourceStatus.ACTIVE, Instant.EPOCH, Instant.EPOCH));
        String operationJson = objectMapper.writeValueAsString(new OperationResponse(
                "op-123456789012", OperationType.CREATE, OperationStatus.RUNNING,
                ProvisioningStage.CREATING_DATABASE, 25, "Operation is in progress.",
                Instant.EPOCH, null, null));

        assertTrue(projectJson.contains("organizationId"));
        assertFalse(projectJson.contains("namespace"));
        assertFalse(operationJson.contains("databaseId"));
        assertFalse(operationJson.contains("project"));
    }
}
