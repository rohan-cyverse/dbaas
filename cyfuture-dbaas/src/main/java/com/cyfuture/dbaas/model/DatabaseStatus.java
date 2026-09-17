package com.cyfuture.dbaas.model;

public enum DatabaseStatus {
    PROVISIONING,
    RUNNING,
    MAINTENANCE,
    DEGRADED,
    FAILED,
    DELETING,
    DELETED,
    MISSING,
    ORPHANED,
    UNKNOWN
}
