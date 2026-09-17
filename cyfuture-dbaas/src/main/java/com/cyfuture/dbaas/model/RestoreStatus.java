package com.cyfuture.dbaas.model;

/** Public lifecycle state for an in-place restore request. */
public enum RestoreStatus {
    PENDING,
    SAFETY_BACKUP,
    RESTORING,
    VALIDATING,
    CUTTING_OVER,
    COMPLETED,
    FAILED,
    CANCELLED,
    ROLLING_BACK,
    // Legacy temporary-restore states retained so existing API data still deserializes.
    RUNNING,
    READY,
    DELETING,
    DELETED,
    EXPIRED
}
