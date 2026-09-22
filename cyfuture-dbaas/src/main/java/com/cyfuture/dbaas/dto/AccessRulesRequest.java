package com.cyfuture.dbaas.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
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
        List<String> removeCidrs,
        @Schema(
                description = "Single IPv4 CIDR to add. Backwards-compatible alias for clients that do not send addCidrs.",
                example = "49.50.73.146/32"
        )
        String cidr,
        @Schema(
                description = "Single IPv4 CIDR to add. Backwards-compatible alias for addCidrs.",
                example = "49.50.73.146/32"
        )
        String addCidr,
        @Schema(
                description = "Single IPv4 CIDR to remove. Backwards-compatible alias for removeCidrs.",
                example = "203.0.113.0/24"
        )
        String removeCidr
) {
    public AccessRulesRequest {
        if (allowedCidrs == null) allowedCidrs = List.of();
        if (addCidrs == null) addCidrs = List.of();
        if (removeCidrs == null) removeCidrs = List.of();
    }

    public AccessRulesRequest(List<String> allowedCidrs, boolean includeCurrentClientIp) {
        this(allowedCidrs, includeCurrentClientIp, List.of(), List.of(), null, null, null);
    }

    public AccessRulesRequest(List<String> allowedCidrs, boolean includeCurrentClientIp,
                              List<String> addCidrs, List<String> removeCidrs) {
        this(allowedCidrs, includeCurrentClientIp, addCidrs, removeCidrs, null, null, null);
    }

    public List<String> cidrsToAdd() {
        List<String> values = new ArrayList<>(allowedCidrs);
        values.addAll(addCidrs);
        addIfPresent(values, cidr);
        addIfPresent(values, addCidr);
        return values;
    }

    public List<String> cidrsToRemove() {
        List<String> values = new ArrayList<>(removeCidrs);
        addIfPresent(values, removeCidr);
        return values;
    }

    private static void addIfPresent(List<String> values, String value) {
        if (value != null && !value.isBlank()) values.add(value);
    }
}
