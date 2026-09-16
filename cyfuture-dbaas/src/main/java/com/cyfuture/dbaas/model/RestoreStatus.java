package com.cyfuture.dbaas.model;

/** Public lifecycle state for a restore-to-new-database request. */
public enum RestoreStatus {
    PENDING,
    RUNNING,
    READY,
    COMPLETED,
    FAILED,
    DELETING,
    DELETED,
    EXPIRED
}
