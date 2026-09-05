package com.cyfuture.dbaas.dto;

import jakarta.validation.constraints.Size;

/** displayName is optional; a friendly random name is generated when omitted. */
public record CreateOrganizationRequest(
        @Size(min = 2, max = 64) String displayName,
        @Size(max = 250) String description
) {}
