package com.cyfuture.dbaas.model;

public enum OperationStatus {
    PENDING,
    RUNNING,
    CANCEL_REQUESTED,
    CANCELLING,
    CANCELLED,
    SUCCEEDED,
    FAILED
}
