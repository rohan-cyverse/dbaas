package com.cyfuture.dbaas.model;

/** Full backup is available now; the other values reserve a stable API model. */
public enum BackupType {
    FULL,
    INCREMENTAL,
    CONTINUOUS
}
