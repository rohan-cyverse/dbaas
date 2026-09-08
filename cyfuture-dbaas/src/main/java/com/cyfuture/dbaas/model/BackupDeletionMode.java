package com.cyfuture.dbaas.model;

/** Distinguishes removing a Backup CR from deleting its retained data. */
public enum BackupDeletionMode {
    CR_ONLY,
    PURGE_DATA
}
