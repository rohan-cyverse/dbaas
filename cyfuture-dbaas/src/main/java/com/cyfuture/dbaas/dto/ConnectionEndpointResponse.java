package com.cyfuture.dbaas.dto;

public record ConnectionEndpointResponse(
        String host,
        int port,
        boolean ready,
        String uri
) {
}
