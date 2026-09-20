package com.cyfuture.dbaas.model;

/** User-facing stages for the asynchronous database creation workflow. */
public enum ProvisioningStage {
    QUEUED,
    VALIDATING,
    CREATING_SAFETY_BACKUP,
    ENTERING_MAINTENANCE,
    QUIESCING_TOPOLOGY,
    REPLACING_DATA,
    CUTTING_OVER,
    ROLLING_BACK,
    CREATING_DATABASE,
    RESTORING_DATA,
    CREATING_SAFETY_BACKUP,
    WAITING_FOR_REPLICAS,
    CREATING_CREDENTIALS,
    CONFIGURING_NETWORK,
    VERIFYING_CONNECTION,
    READY,
    FAILED
}
