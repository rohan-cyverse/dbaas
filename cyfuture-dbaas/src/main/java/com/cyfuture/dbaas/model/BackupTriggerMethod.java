package com.cyfuture.dbaas.model;

/** Identifies whether DBaaS or the KubeBlocks schedule created a backup. */
public enum BackupTriggerMethod {
    MANUAL,
    AUTOMATIC
}
