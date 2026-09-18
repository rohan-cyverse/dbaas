package com.cyfuture.dbaas.dto;

import com.cyfuture.dbaas.model.DatabaseEngine;
import com.cyfuture.dbaas.model.DatabaseStatus;
import com.cyfuture.dbaas.model.DatabaseMode;
import com.cyfuture.dbaas.model.SizePlan;
import com.cyfuture.dbaas.model.ProvisioningStage;

public record DatabaseResponse(
        String databaseId,
        String name,
        DatabaseEngine engine,
        String version,
        DatabaseStatus status,
        DatabaseMode deploymentMode,
        SizePlan sizePlan,
        int storageGi,
        int instanceCount,
        int primaryCount,
        int replicaCount,
        int shardCount,
        int mongosCount,
        int configServerCount,
        boolean deletionProtection,
        ProvisioningStage stage,
        int progress,
        PublicEndpointResponse endpoint,
        DatabaseTopologyResponse topology,
        String message
) {
}
