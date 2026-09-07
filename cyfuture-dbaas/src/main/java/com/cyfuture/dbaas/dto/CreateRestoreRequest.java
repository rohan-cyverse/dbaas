package com.cyfuture.dbaas.dto;

import jakarta.validation.constraints.Pattern;

import java.time.Instant;

public record CreateRestoreRequest(
        @Pattern(regexp = "^[a-z0-9]([-a-z0-9]{0,30}[a-z0-9])?$",
                message = "name must be a DNS-compatible database name")
        String name,
        Instant restoreTime
) {}
