package com.cyfuture.dbaas.service;

import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

@Component
public class GatewayReconciliationLock {
    private static final String NAME = "dbaas-public-gateway-reconcile";
    private final DataSource dataSource;
    private Connection leaderConnection;

    public GatewayReconciliationLock(DataSource dataSource) { this.dataSource = dataSource; }

    /**
     * Keeps the MySQL named lock for the lifetime of this control-plane
     * instance. Releasing it after every pass lets mixed-version instances
     * alternate different gateway configs and continuously roll HAProxy Pods.
     * MySQL automatically releases the lock if this process or connection dies.
     */
    public synchronized void execute(Runnable task) {
        try {
            Connection c = leaderConnection;
            if (c == null || c.isClosed() || !c.isValid(2)) {
                closeLeaderConnection();
                c = dataSource.getConnection();
                boolean locked;
                try (PreparedStatement p = c.prepareStatement("SELECT GET_LOCK(?, 10)")) {
                    p.setString(1, NAME);
                    try (ResultSet r = p.executeQuery()) {
                        locked = r.next() && r.getInt(1) == 1;
                    }
                }
                if (!locked) {
                    c.close();
                    throw new IllegalStateException("Could not acquire gateway reconciliation leadership");
                }
                leaderConnection = c;
            }
            task.run();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            closeLeaderConnection();
            throw new IllegalStateException("Gateway lock failed", e);
        }
    }

    @PreDestroy
    public synchronized void close() {
        if (leaderConnection != null) {
            try (PreparedStatement p = leaderConnection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                p.setString(1, NAME);
                p.executeQuery();
            } catch (Exception ignored) {
                // Closing the connection releases a surviving named lock.
            }
        }
        closeLeaderConnection();
    }

    private void closeLeaderConnection() {
        if (leaderConnection == null) return;
        try {
            leaderConnection.close();
        } catch (Exception ignored) {
            // Best effort; the pool/driver will discard a broken connection.
        } finally {
            leaderConnection = null;
        }
    }
}
