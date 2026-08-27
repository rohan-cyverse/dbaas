package com.cyfuture.dbaas.model;

public enum DatabaseStatus {
    PROVISIONING,
    RUNNING,
    DEGRADED,
    FAILED,
    DELETING,
    DELETED,
    MISSING,
    ORPHANED,
    UNKNOWN
}
