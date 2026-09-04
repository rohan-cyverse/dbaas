package com.cyfuture.dbaas.service;

import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

@Component
public class GatewayReconciliationLock {
    private static final String NAME = "dbaas-public-gateway-reconcile";
    private final DataSource dataSource;
    public GatewayReconciliationLock(DataSource dataSource) { this.dataSource = dataSource; }
    public void execute(Runnable task) {
        try (Connection c = dataSource.getConnection()) {
            boolean locked;
            try (PreparedStatement p = c.prepareStatement("SELECT GET_LOCK(?, 10)")) {
                p.setString(1, NAME);
                try (ResultSet r = p.executeQuery()) { locked = r.next() && r.getInt(1) == 1; }
            }
            if (!locked) throw new IllegalStateException("Could not acquire gateway reconciliation lock");
            try { task.run(); }
            finally { try (PreparedStatement p = c.prepareStatement("SELECT RELEASE_LOCK(?)")) { p.setString(1, NAME); p.executeQuery(); } }
        } catch (RuntimeException e) { throw e; } catch (Exception e) { throw new IllegalStateException("Gateway lock failed", e); }
    }
}
