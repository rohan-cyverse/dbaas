package com.cyfuture.dbaas.dto;

import java.util.List;

public record PublicEndpointResponse(
        String host,
        int port,
        boolean ready,
        List<String> allowedCidrs
) {
}
