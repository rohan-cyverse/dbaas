package com.cyfuture.dbaas.model;

/**
 * DBaaS retention intent. KubeBlocks remains responsible for enforcing the
 * retention period on a Backup CR; DBaaS coordinates replacement and purge
 * ordering around that observed state.
 */
public enum BackupRetentionPolicy {
    DELETE_ALL,
    RETAIN_LATEST,
    RETAIN_ALL
}
