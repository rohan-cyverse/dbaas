package com.cyfuture.dbaas.dto;

import com.cyfuture.dbaas.model.DatabaseEngine;

public record ConnectionResponse(
        String databaseId,
        DatabaseEngine engine,
        String defaultDatabase,
        String username,
        String password,
        String privateConnectionUri,
        String publicConnectionUri,
        PrivateEndpointResponse privateEndpoint,
        PublicEndpointResponse publicEndpoint
) {
}
