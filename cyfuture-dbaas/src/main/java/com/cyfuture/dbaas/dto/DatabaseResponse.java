package com.cyfuture.dbaas.dto;

import com.cyfuture.dbaas.model.DatabaseEngine;
import com.cyfuture.dbaas.model.DatabaseStatus;
import com.cyfuture.dbaas.model.DatabaseMode;
import com.cyfuture.dbaas.model.SizePlan;
import com.cyfuture.dbaas.model.ProvisioningStage;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;

public record DatabaseResponse(
        String databaseId,
        String project,
        @JsonIgnore @Schema(hidden = true)
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
        @JsonIgnore @Schema(hidden = true)
        int readyReplicas,
        @JsonIgnore @Schema(hidden = true)
        int readyVolumes,
        @JsonIgnore @Schema(hidden = true)
        boolean serviceReady,
        @JsonIgnore @Schema(hidden = true)
        PrivateEndpointResponse privateEndpoint,
        PublicEndpointResponse publicEndpoint,
        String message
) {
}
