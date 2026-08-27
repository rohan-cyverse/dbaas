package com.cyfuture.dbaas.dto;

public record PrivateEndpointResponse(
        String host,
        int port,
        boolean ready
) {
}
