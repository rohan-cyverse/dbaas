package com.cyfuture.dbaas.dto;

import java.util.List;

public record DatabaseTopologyResponse(
        int instanceCount,
        int primaryCount,
        int replicaCount,
        int shardCount,
        int mongosCount,
        int configServerCount,
        List<DatabaseTopologyMemberResponse> members
) {
    public DatabaseTopologyResponse {
        if (members == null) members = List.of();
    }
}
