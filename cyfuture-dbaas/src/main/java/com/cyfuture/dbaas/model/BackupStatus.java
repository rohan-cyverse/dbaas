package com.cyfuture.dbaas.model;

/** Public lifecycle state for backup objects. */
public enum BackupStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    DELETING,
    DELETED
}
