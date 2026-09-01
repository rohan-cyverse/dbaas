package com.cyfuture.dbaas.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record PublicEndpointResponse(
        String host,
        int port,
        boolean ready,
        @JsonIgnore @Schema(hidden = true)
        List<String> allowedCidrs
) {
}
