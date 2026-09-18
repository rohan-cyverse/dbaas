package com.cyfuture.dbaas.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

import java.util.List;

public record AccessRulesRequest(
        @Schema(
                description = "IPv4 CIDRs allowed to connect through the public endpoint.",
                example = "[\"49.50.73.146/32\",\"203.0.113.0/24\"]"
        )
        @Size(max = 10)
        List<String> allowedCidrs,
        @Schema(
                description = "When true, the caller's detected public IP is added as /32.",
                example = "true"
        )
        boolean includeCurrentClientIp
) {
    public AccessRulesRequest {
        if (allowedCidrs == null) allowedCidrs = List.of();
    }
}
