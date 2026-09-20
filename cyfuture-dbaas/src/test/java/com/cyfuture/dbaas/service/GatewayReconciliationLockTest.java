package com.cyfuture.dbaas.service;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GatewayReconciliationLockTest {

    @Test
    void holdsLeadershipUntilTheComponentCloses() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement acquire = mock(PreparedStatement.class);
        PreparedStatement release = mock(PreparedStatement.class);
        ResultSet acquireResult = mock(ResultSet.class);
        Runnable task = mock(Runnable.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT GET_LOCK(?, 10)")).thenReturn(acquire);
        when(connection.prepareStatement("SELECT RELEASE_LOCK(?)")).thenReturn(release);
        when(acquire.executeQuery()).thenReturn(acquireResult);
        when(acquireResult.next()).thenReturn(true);
        when(acquireResult.getInt(1)).thenReturn(1);
        when(connection.isValid(2)).thenReturn(true);

        GatewayReconciliationLock lock = new GatewayReconciliationLock(dataSource);
        lock.execute(task);
        lock.execute(task);
        lock.close();

        InOrder connectionOrder = inOrder(connection);
        connectionOrder.verify(connection).prepareStatement("SELECT GET_LOCK(?, 10)");
        connectionOrder.verify(connection).prepareStatement("SELECT RELEASE_LOCK(?)");
        verify(acquire).setString(1, "dbaas-public-gateway-reconcile");
        verify(release).setString(1, "dbaas-public-gateway-reconcile");
        verify(task, org.mockito.Mockito.times(2)).run();
        verify(dataSource).getConnection();
        verify(connection).close();
    }
}
