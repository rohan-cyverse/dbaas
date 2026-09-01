package com.cyfuture.dbaas.dto;

import com.cyfuture.dbaas.model.DatabaseEngine;
import com.cyfuture.dbaas.model.DatabaseStatus;
import com.cyfuture.dbaas.model.DatabaseMode;
import com.cyfuture.dbaas.model.SizePlan;
import com.cyfuture.dbaas.model.ProvisioningStage;
import com.fasterxml.jackson.annotation.JsonIgnore;

public record DatabaseResponse(
        String databaseId,
        String project,
        String namespace,
        String name,
        DatabaseEngine engine,
        DatabaseMode mode,
        String version,
        SizePlan size,
        int storageGi,
        boolean deletionProtection,
        DatabaseStatus status,
        ProvisioningStage stage,
        int progress,
        int replicas,
        int readyReplicas,
        int readyVolumes,
        boolean serviceReady,
        @JsonIgnore
        PrivateEndpointResponse privateEndpoint,
        PublicEndpointResponse publicEndpoint,
        String message
) {
}
