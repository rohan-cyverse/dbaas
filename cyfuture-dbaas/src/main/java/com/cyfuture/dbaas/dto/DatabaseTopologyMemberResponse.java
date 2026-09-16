package com.cyfuture.dbaas.dto;

public record DatabaseTopologyMemberResponse(
        String name,
        String role,
        String component,
        boolean ready
) {
}
