package com.cyfuture.dbaas.dto;

public record ConnectionResponse(
        String username,
        String password,
        String connectionUri,
        PublicEndpointResponse endpoint,
        String database,
        String host,
        int port,
        boolean ready,
        ConnectionEndpointResponse readWrite,
        ConnectionEndpointResponse readOnly,
        java.util.List<String> allowedCidrs
) {
}
