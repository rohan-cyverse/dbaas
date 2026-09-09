package com.cyfuture.dbaas.dto;

import com.cyfuture.dbaas.model.PitrStatus;

import java.time.Instant;

/** Safe, observed PITR coverage. It never contains object-store or Secret data. */
public record RecoveryWindowResponse(
        String project,
        String databaseId,
        boolean pitrEnabled,
        PitrStatus status,
        String continuousMethod,
        Instant recoverableFrom,
        Instant recoverableUntil,
        String message,
        Instant observedAt
) {}
