package com.cyfuture.dbaas.service;

import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class GatewayReconciliationLockTest {

    @Test
    void serializesLocallyWithoutAStaleExternalLock() {
        DataSource dataSource = mock(DataSource.class);
        Runnable task = mock(Runnable.class);
        GatewayReconciliationLock lock = new GatewayReconciliationLock(dataSource);
        lock.execute(task);
        lock.execute(task);
        verify(task, org.mockito.Mockito.times(2)).run();
        verifyNoInteractions(dataSource);
    }
}
