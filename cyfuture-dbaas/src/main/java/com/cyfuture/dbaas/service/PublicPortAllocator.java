package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.config.DatabaseProperties;
import com.cyfuture.dbaas.exception.ApiException;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PublicPortAllocator {
    private final DatabaseMetadataRepository databaseRepository;
    private final DatabaseProperties properties;

    public synchronized int allocate() {
        return allocateExcluding(java.util.Set.of());
    }

    /**
     * Allocates a port which is unused by either endpoint column and is not
     * one of the caller's already assigned ports.
     */
    public synchronized int allocateExcluding(java.util.Set<Integer> excludedPorts) {
        for (int port = SharedGatewayService.PUBLIC_PORT_START;
             port <= SharedGatewayService.PUBLIC_PORT_END; port++) {
            if (!excludedPorts.contains(port)
                    && !databaseRepository.existsByPublicPort(port)
                    && !databaseRepository.existsByReadOnlyPublicPort(port)) return port;
        }
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "Shared public gateway capacity is exhausted for ports "
                        + SharedGatewayService.PUBLIC_PORT_START + "-"
                        + SharedGatewayService.PUBLIC_PORT_END);
    }
}
