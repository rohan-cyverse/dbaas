package com.cyfuture.dbaas.client;

import com.cyfuture.dbaas.model.DatabaseEngine;
import com.cyfuture.dbaas.model.DatabaseMode;
import com.cyfuture.dbaas.model.DatabaseStatus;
import com.cyfuture.dbaas.model.SizePlan;
import com.fasterxml.jackson.annotation.JsonIgnoreType;

/**
 * Runtime-only KubeBlocks observation. It intentionally contains internal
 * service details needed by the control plane but is never serialized to API
 * clients.
 */
@JsonIgnoreType
public record DatabaseObservation(
        String databaseId,
        String displayName,
        DatabaseEngine engine,
        DatabaseMode mode,
        String version,
        SizePlan size,
        int storageGi,
        boolean deletionProtection,
        DatabaseStatus status,
        int replicas,
        int readyReplicas,
        int readyVolumes,
        boolean serviceReady,
        String privateHost,
        int privatePort,
        String message
) {}
