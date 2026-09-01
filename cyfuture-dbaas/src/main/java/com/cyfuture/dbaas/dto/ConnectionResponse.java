package com.cyfuture.dbaas.dto;

import com.cyfuture.dbaas.model.DatabaseEngine;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;

public record ConnectionResponse(
        String databaseId,
        DatabaseEngine engine,
        String defaultDatabase,
        String username,
        String password,
        @JsonIgnore @Schema(hidden = true)
        String privateConnectionUri,
        String publicConnectionUri,
        @JsonIgnore @Schema(hidden = true)
        PrivateEndpointResponse privateEndpoint,
        PublicEndpointResponse publicEndpoint
) {
}
