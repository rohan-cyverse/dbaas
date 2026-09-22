package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.config.DatabaseProperties;
import com.cyfuture.dbaas.exception.ApiException;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublicPortAllocatorTest {

    @Test
    void allocatesAcrossTheConfiguredGatewayRange() {
        assertEquals(31000, allocateFirstAvailable(31000));
        assertEquals(31010, allocateFirstAvailable(31010));
        assertEquals(31020, allocateFirstAvailable(31020));
        assertEquals(31030, allocateFirstAvailable(31030));
    }

    @Test
    void allocationBeyondTheOldTenPortPoolWorks() {
        assertEquals(31010, allocateFirstAvailable(31010));
    }

    @Test
    void portOutsideConfiguredRangeIsNotAllocated() {
        DatabaseMetadataRepository repository = mock(DatabaseMetadataRepository.class);
        DatabaseProperties properties = properties();
        for (int port = 31000; port <= 31030; port++) {
            when(repository.existsByPublicPort(port)).thenReturn(true);
        }
        when(repository.existsByPublicPort(31031)).thenReturn(false);

        ApiException exception = assertThrows(ApiException.class,
                () -> new PublicPortAllocator(repository, properties).allocate());

        assertTrue(exception.getMessage().contains("31000-31030"));
    }

    @Test
    void ignoresConfiguredRangeOverrides() {
        DatabaseMetadataRepository repository = mock(DatabaseMetadataRepository.class);
        DatabaseProperties properties = properties();
        properties.getGateway().setPortStart(32000);
        properties.getGateway().setPortEnd(32010);
        when(repository.existsByPublicPort(31000)).thenReturn(false);

        assertEquals(31000, new PublicPortAllocator(repository, properties).allocate());
    }

    private int allocateFirstAvailable(int firstAvailable) {
        DatabaseMetadataRepository repository = mock(DatabaseMetadataRepository.class);
        for (int port = 31000; port < firstAvailable; port++) {
            when(repository.existsByPublicPort(port)).thenReturn(true);
        }
        when(repository.existsByPublicPort(firstAvailable)).thenReturn(false);
        return new PublicPortAllocator(repository, properties()).allocate();
    }

    private DatabaseProperties properties() {
        DatabaseProperties properties = new DatabaseProperties();
        properties.getGateway().setPortStart(31000);
        properties.getGateway().setPortEnd(31030);
        return properties;
    }
}
