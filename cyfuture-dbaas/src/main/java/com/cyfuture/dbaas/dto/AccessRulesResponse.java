package com.cyfuture.dbaas.dto;

import java.util.List;

public record AccessRulesResponse(
        String databaseId,
        List<String> allowedCidrs,
        PublicEndpointResponse endpoint
) {
}
