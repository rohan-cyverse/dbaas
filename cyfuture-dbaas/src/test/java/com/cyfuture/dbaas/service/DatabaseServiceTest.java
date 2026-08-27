package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.config.DatabaseProperties;
import com.cyfuture.dbaas.dto.CreateDatabaseRequest;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.entity.OperationMetadata;
import com.cyfuture.dbaas.entity.ProjectMetadata;
import com.cyfuture.dbaas.exception.ApiException;
import com.cyfuture.dbaas.model.DatabaseEngine;
import com.cyfuture.dbaas.model.DatabaseMode;
import com.cyfuture.dbaas.model.SizePlan;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import com.cyfuture.dbaas.repository.OperationMetadataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseServiceTest {
    private DatabaseMetadataRepository repository;
    private AsyncProvisioningService provisioning;
    private MetadataCreationService metadataCreation;
    private ProjectService projects;
    private DatabaseService service;

    @BeforeEach
    void setUp() {
        repository = mock(DatabaseMetadataRepository.class);
        provisioning = mock(AsyncProvisioningService.class);
        metadataCreation = mock(MetadataCreationService.class);
        projects = mock(ProjectService.class);
        DatabaseProperties properties = new DatabaseProperties();
        properties.getPostgresql().setVersions(List.of("17.5.0"));
        ProjectMetadata project = new ProjectMetadata();
        project.setProjectName("orders");
        project.setNamespaceName("dbaas-orders");
        when(projects.requireActiveProject("orders")).thenReturn(project);
        when(repository.findByProjectNameAndIdempotencyKey(anyString(), anyString()))
                .thenReturn(Optional.empty());
        service = new DatabaseService(mock(KubeBlocksClient.class), properties, repository,
                provisioning, metadataCreation, mock(CredentialLifecycleService.class),
                projects, mock(SharedGatewayService.class), mock(OperationMetadataRepository.class));
    }

    @Test
    void createStoresProjectScopedMetadataAndCallerCidr() {
        service.create("orders", "create-orders-001", request(), "157.37.137.185");

        ArgumentCaptor<DatabaseMetadata> database = ArgumentCaptor.forClass(DatabaseMetadata.class);
        ArgumentCaptor<OperationMetadata> operation = ArgumentCaptor.forClass(OperationMetadata.class);
        verify(metadataCreation).save(database.capture(), operation.capture());
        assertEquals("orders", database.getValue().getProjectName());
        assertEquals("dbaas-orders", database.getValue().getNamespaceName());
        assertEquals("orders", operation.getValue().getProjectName());
        verify(provisioning).provision(anyString(), anyString(),
                anyString(), anyString(), any(CreateDatabaseRequest.class));
    }

    @Test
    void createRequiresDetectableCallerIp() {
        assertThrows(ApiException.class,
                () -> service.create("orders", "create-orders-002", request(), null));
    }

    private CreateDatabaseRequest request() {
        return new CreateDatabaseRequest("orders-db", "Orders", DatabaseEngine.POSTGRESQL,
                DatabaseMode.STANDALONE, "17.5.0", SizePlan.C1G2, 10, 1, 0,
                "Asia/Kolkata", null, true, Map.of("env", "test"));
    }
}
