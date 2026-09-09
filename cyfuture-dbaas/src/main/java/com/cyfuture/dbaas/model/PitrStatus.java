package com.cyfuture.dbaas.model;

/** Health of the observed continuous-log recovery chain, not merely desired state. */
public enum PitrStatus {
    DISABLED,
    PENDING,
    READY,
    UNHEALTHY
}
