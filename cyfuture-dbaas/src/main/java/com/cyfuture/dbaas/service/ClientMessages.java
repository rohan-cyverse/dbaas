package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.model.DatabaseStatus;
import com.cyfuture.dbaas.model.OperationStatus;
import com.cyfuture.dbaas.model.ProvisioningStage;

/**
 * Public, stable lifecycle messages. Runtime diagnostics stay in logs and
 * operation metadata so Kubernetes and provider details never become API
 * contracts.
 */
public final class ClientMessages {
    private ClientMessages() {
    }

    public static String database(DatabaseStatus status, ProvisioningStage stage) {
        if (status == DatabaseStatus.RUNNING) return "Database is ready.";
        if (status == DatabaseStatus.DELETING) return "Deletion is in progress.";
        if (status == DatabaseStatus.DELETED) return "Database deleted.";
        if (status == DatabaseStatus.DEGRADED) return "Database health is degraded.";
        if (status == DatabaseStatus.MISSING) return "Database resource is unavailable.";
        if (status == DatabaseStatus.FAILED) return "Provisioning failed.";
        if (status == DatabaseStatus.ORPHANED) return "Unmanaged database resource detected.";
        if (status == DatabaseStatus.PROVISIONING) return provisioning(stage);
        return "Database status is being updated.";
    }

    public static String operation(OperationStatus status) {
        return switch (status) {
            case PENDING -> "Operation is queued.";
            case RUNNING -> "Operation is in progress.";
            case SUCCEEDED -> "Operation completed.";
            case FAILED -> "Operation failed.";
        };
    }

    public static String provisioning(ProvisioningStage stage) {
        if (stage == null) return "Provisioning is queued.";
        return switch (stage) {
            case QUEUED -> "Provisioning is queued.";
            case VALIDATING -> "Validating configuration.";
            case CREATING_DATABASE -> "Creating database.";
            case WAITING_FOR_REPLICAS -> "Waiting for database readiness.";
            case CREATING_CREDENTIALS -> "Preparing credentials.";
            case CONFIGURING_NETWORK -> "Configuring public access.";
            case VERIFYING_CONNECTION -> "Verifying connectivity.";
            case READY -> "Database is ready.";
            case FAILED -> "Provisioning failed.";
        };
    }
}
