package com.cyfuture.dbaas.service;

import org.springframework.stereotype.Component;

/**
 * Serializes gateway reconciliation inside this process. Kubernetes
 * resourceVersion provides cross-process optimistic concurrency; conflicts
 * fail with 409 and are retried by the scheduled reconciler.
 *
 * MySQL named locks are unsafe here because the metadata database is reached
 * through a tunnel which can preserve a killed JVM's server-side session and
 * permanently block all subsequent gateway updates.
 */
@Component
public class GatewayReconciliationLock {
    public GatewayReconciliationLock(javax.sql.DataSource ignored) {
        // Constructor retained for Spring/test wiring compatibility.
    }

    public synchronized void execute(Runnable task) {
        task.run();
    }
}
