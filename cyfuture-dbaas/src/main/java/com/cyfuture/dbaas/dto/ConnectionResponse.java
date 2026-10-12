package com.cyfuture.dbaas.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

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
        @JsonInclude(JsonInclude.Include.NON_NULL) ConnectionEndpointResponse readOnly,
        java.util.List<String> allowedCidrs
) {
}
