package com.cyfuture.dbaas.model;

/** Public lifecycle state of a desired scheduled-backup configuration. */
public enum BackupPolicyStatus {
    PENDING,
    ACTIVE,
    FAILED,
    DISABLED
}
