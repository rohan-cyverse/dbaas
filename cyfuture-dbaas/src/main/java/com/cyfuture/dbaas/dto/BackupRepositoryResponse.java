package com.cyfuture.dbaas.dto;

/** Read-only repository summary. It intentionally never contains Secret references or values. */
public record BackupRepositoryResponse(
        String name,
        String provider,
        boolean defaultRepository,
        boolean ready
) {}
