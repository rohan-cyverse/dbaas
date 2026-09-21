package com.cyfuture.dbaas.client;

import com.cyfuture.dbaas.model.DatabaseEngine;
import com.cyfuture.dbaas.model.DatabaseMode;
import com.cyfuture.dbaas.model.DatabaseStatus;
import com.cyfuture.dbaas.model.SizePlan;
import com.fasterxml.jackson.annotation.JsonIgnoreType;

import java.util.List;

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
        int instanceCount,
        int primaryCount,
        int replicaCount,
        int shardCount,
        int mongosCount,
        int configServerCount,
        int readyReplicas,
        int readyVolumes,
        boolean serviceReady,
        String privateHost,
        int privatePort,
        List<TopologyMember> members,
        String message
) {
    public DatabaseObservation {
        if (members == null) members = List.of();
    }

    /** Backwards-compatible name used by topology observation callers. */
    public int replicas() {
        return mode == DatabaseMode.SHARDING ? shardCount : instanceCount;
    }

    public record TopologyMember(
            String name,
            String role,
            String component,
            boolean ready
    ) {}
}
