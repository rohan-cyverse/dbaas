package com.cyfuture.dbaas.model;

/** User-facing stages for the asynchronous database creation workflow. */
public enum ProvisioningStage {
    QUEUED,
    VALIDATING,
    CREATING_DATABASE,
    RESTORING_DATA,
    WAITING_FOR_REPLICAS,
    CREATING_CREDENTIALS,
    CONFIGURING_NETWORK,
    VERIFYING_CONNECTION,
    READY,
    FAILED
}
