package com.cyfuture.dbaas.dto;

import com.cyfuture.dbaas.model.PitrStatus;

import java.time.Instant;

/** Product-level observed PITR coverage. */
public record RecoveryWindowResponse(
        String databaseId,
        boolean enabled,
        PitrStatus status,
        Instant startsAt,
        Instant endsAt,
        String errorCode,
        String errorMessage,
        Instant observedAt
) {}
