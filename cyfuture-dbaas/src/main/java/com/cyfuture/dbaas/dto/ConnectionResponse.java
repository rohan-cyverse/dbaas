package com.cyfuture.dbaas.dto;

import com.cyfuture.dbaas.model.DatabaseEngine;
import com.fasterxml.jackson.annotation.JsonIgnore;

public record ConnectionResponse(
        String databaseId,
        DatabaseEngine engine,
        String defaultDatabase,
        String username,
        String password,
        @JsonIgnore
        String privateConnectionUri,
        String publicConnectionUri,
        @JsonIgnore
        PrivateEndpointResponse privateEndpoint,
        PublicEndpointResponse publicEndpoint
) {
}
