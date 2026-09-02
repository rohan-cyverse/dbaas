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
        DatabaseProperties.GatewaySettings gateway = properties.getGateway();
        for (int port = gateway.getPortStart(); port <= gateway.getPortEnd(); port++) {
            if (!databaseRepository.existsByPublicPort(port)) return port;
        }
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "Shared public gateway capacity is exhausted");
    }
}
