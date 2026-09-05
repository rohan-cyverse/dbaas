package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.entity.OperationMetadata;
import com.cyfuture.dbaas.exception.ApiException;
import com.cyfuture.dbaas.mapper.OperationMapper;
import com.cyfuture.dbaas.model.OperationStatus;
import com.cyfuture.dbaas.model.OperationType;
import com.cyfuture.dbaas.model.ProvisioningStage;
import com.cyfuture.dbaas.repository.OperationMetadataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OperationServiceTest {
    private OperationMetadataRepository repository;
    private OperationService service;

    @BeforeEach
    void setUp() {
        repository = mock(OperationMetadataRepository.class);
        service = new OperationService(repository, new OperationMapper());
    }

    @Test
    void scopesOperationByProjectAndDatabase() {
        when(repository.findByOperationIdAndDatabaseIdAndProjectName(
                "op-create0001", "db-orders0001", "orders"))
                .thenReturn(Optional.of(operation()));

        var response = service.getForDatabase("orders", "db-orders0001", "op-create0001");
        assertEquals("op-create0001", response.operationId());
        assertEquals(OperationType.CREATE, response.type());
    }

    @Test
    void rejectsOperationOutsideRequestedDatabase() {
        when(repository.findByOperationIdAndDatabaseIdAndProjectName(
                "op-create0001", "db-billing001", "orders"))
                .thenReturn(Optional.empty());
        ApiException exception = assertThrows(ApiException.class,
                () -> service.getForDatabase("orders", "db-billing001", "op-create0001"));
        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        verify(repository).findByOperationIdAndDatabaseIdAndProjectName(
                "op-create0001", "db-billing001", "orders");
    }

    private OperationMetadata operation() {
        return OperationMetadata.builder()
                .operationId("op-create0001")
                .databaseId("db-orders0001")
                .projectName("orders")
                .type(OperationType.CREATE)
                .status(OperationStatus.SUCCEEDED)
                .provisioningStage(ProvisioningStage.READY)
                .progress(100)
                .message("done")
                .createdAt(Instant.now())
                .build();
    }
}
