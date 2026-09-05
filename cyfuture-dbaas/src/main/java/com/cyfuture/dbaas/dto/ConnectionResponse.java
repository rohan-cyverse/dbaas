package com.cyfuture.dbaas.dto;

public record ConnectionResponse(
        String username,
        String password,
        String connectionUri,
        PublicEndpointResponse endpoint
) {
}
