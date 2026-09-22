package com.cyfuture.dbaas.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

import java.util.List;

public record AccessRulesRequest(
        @Schema(
                description = "IPv4 CIDRs to add to the public endpoint allow-list. Kept for backwards compatibility.",
                example = "[\"49.50.73.146/32\",\"203.0.113.0/24\"]"
        )
        @Size(max = 10)
        List<String> allowedCidrs,
        @Schema(
                description = "When true, the caller's detected public IP is added as /32.",
                example = "true"
        )
        boolean includeCurrentClientIp,
        @Schema(
                description = "IPv4 CIDRs to add to the public endpoint allow-list.",
                example = "[\"49.50.73.146/32\"]"
        )
        @Size(max = 10)
        List<String> addCidrs,
        @Schema(
                description = "IPv4 CIDRs to remove from the public endpoint allow-list.",
                example = "[\"203.0.113.0/24\"]"
        )
        @Size(max = 10)
        List<String> removeCidrs
) {
    public AccessRulesRequest {
        if (allowedCidrs == null) allowedCidrs = List.of();
        if (addCidrs == null) addCidrs = List.of();
        if (removeCidrs == null) removeCidrs = List.of();
    }

    public AccessRulesRequest(List<String> allowedCidrs, boolean includeCurrentClientIp) {
        this(allowedCidrs, includeCurrentClientIp, List.of(), List.of());
    }
}
