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
        DatabaseMode mode,
        String version,
        SizePlan size,
        int storageGi,
        int replicas,
        int shards,
        boolean deletionProtection,
        DatabaseStatus status,
        ProvisioningStage stage,
        int progress,
        PublicEndpointResponse endpoint,
        String message
) {
}
