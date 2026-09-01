package com.cyfuture.dbaas.controller;

import com.cyfuture.dbaas.dto.ConnectionResponse;
import com.cyfuture.dbaas.dto.CreateDatabaseRequest;
import com.cyfuture.dbaas.dto.CreateDatabaseResponse;
import com.cyfuture.dbaas.dto.DatabaseResponse;
import com.cyfuture.dbaas.dto.HorizontalScalingRequest;
import com.cyfuture.dbaas.dto.OperationResponse;
import com.cyfuture.dbaas.dto.PageResponse;
import com.cyfuture.dbaas.dto.RestartRequest;
import com.cyfuture.dbaas.dto.StorageExpansionRequest;
import com.cyfuture.dbaas.dto.VerticalScalingRequest;
import com.cyfuture.dbaas.service.ClientIpResolver;
import com.cyfuture.dbaas.service.DatabaseOperationService;
import com.cyfuture.dbaas.service.DatabaseService;
import com.cyfuture.dbaas.service.OperationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/projects/{project}/databases")
@RequiredArgsConstructor
@Tag(
        name = "Databases",
        description = "Database provisioning and lifecycle APIs"
)
public class DatabaseController {
    private final DatabaseService databaseService;
    private final DatabaseOperationService databaseOperationService;
    private final OperationService operationService;
    private final ClientIpResolver clientIpResolver;

    @GetMapping("/options")
    @Operation(
            summary = "Get supported database options",
            description = "Compatibility alias for /api/v1/databases/options. Options are global and do not depend on project state."
    )
    public Map<?, ?> options(@PathVariable String project) {
        return databaseService.options();
    }

    @PostMapping
    @Operation(
            summary = "Provision a database",
            description = "Starts asynchronous provisioning with automatic public access."
    )
    public ResponseEntity<CreateDatabaseResponse> create(
            @PathVariable String project,
            @Parameter(
                    description = "Unique retry-safe key",
                    example = "create-orders-postgres-001"
            )
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateDatabaseRequest request,
            HttpServletRequest httpRequest
    ) {
        CreateDatabaseResponse response = databaseService.create(
                project,
                idempotencyKey,
                request,
                clientIpResolver.resolve(httpRequest)
        );

        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .header("Location", response.statusUrl())
                .header("Operation-Location", response.operationUrl())
                .header("Retry-After", String.valueOf(response.suggestedPollingIntervalSeconds()))
                .body(response);
    }

    @GetMapping
    @Operation(summary = "List project databases")
    public PageResponse<DatabaseResponse> list(
            @PathVariable String project,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return databaseService.list(project, page, size);
    }

    @GetMapping("/{databaseId}")
    @Operation(
            summary = "Get database deployment status",
            description = "Returns database health and endpoints without credentials."
    )
    public DatabaseResponse get(
            @PathVariable String project,
            @PathVariable String databaseId
    ) {
        return databaseService.get(project, databaseId);
    }

    @GetMapping("/{databaseId}/operations")
    @Operation(summary = "List operations for a database")
    public PageResponse<OperationResponse> operations(
            @PathVariable String project,
            @PathVariable String databaseId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        databaseService.validateDatabaseAccess(project, databaseId);

        return operationService.listForDatabase(
                project,
                databaseId,
                page,
                size
        );
    }

    @PostMapping("/{databaseId}/vertical-scaling")
    @Operation(
            summary = "Scale database compute resources",
            description = "Creates a KubeBlocks OpsRequest of type VerticalScaling for one component."
    )
    public ResponseEntity<OperationResponse> verticalScaling(
            @PathVariable String project,
            @PathVariable String databaseId,
            @Parameter(description = "Unique retry-safe key", example = "scale-orders-compute-001")
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = "application/json",
                            examples = @io.swagger.v3.oas.annotations.media.ExampleObject(
                                    value = "{\"componentName\":\"postgresql\",\"requests\":{\"cpu\":\"1\",\"memory\":\"2Gi\"},\"limits\":{\"cpu\":\"2\",\"memory\":\"4Gi\"}}")))
            @Valid @RequestBody VerticalScalingRequest request
    ) {
        OperationResponse response = databaseOperationService.verticalScaling(project, databaseId,
                idempotencyKey, request);
        return accepted(response);
    }

    @PostMapping("/{databaseId}/horizontal-scaling")
    @Operation(
            summary = "Scale database replicas",
            description = "Creates a KubeBlocks OpsRequest of type HorizontalScaling using the requested final replica count."
    )
    public ResponseEntity<OperationResponse> horizontalScaling(
            @PathVariable String project,
            @PathVariable String databaseId,
            @Parameter(description = "Unique retry-safe key", example = "scale-orders-replicas-001")
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = "application/json",
                            examples = @io.swagger.v3.oas.annotations.media.ExampleObject(
                                    value = "{\"componentName\":\"postgresql\",\"targetReplicas\":3}")))
            @Valid @RequestBody HorizontalScalingRequest request
    ) {
        OperationResponse response = databaseOperationService.horizontalScaling(project, databaseId,
                idempotencyKey, request);
        return accepted(response);
    }

    @PostMapping("/{databaseId}/storage-expansion")
    @Operation(
            summary = "Expand database storage",
            description = "Creates a KubeBlocks OpsRequest of type VolumeExpansion. Shrinking is rejected."
    )
    public ResponseEntity<OperationResponse> storageExpansion(
            @PathVariable String project,
            @PathVariable String databaseId,
            @Parameter(description = "Unique retry-safe key", example = "expand-orders-storage-001")
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = "application/json",
                            examples = @io.swagger.v3.oas.annotations.media.ExampleObject(
                                    value = "{\"componentName\":\"postgresql\",\"volumeName\":\"data\",\"newStorageSize\":\"30Gi\"}")))
            @Valid @RequestBody StorageExpansionRequest request
    ) {
        OperationResponse response = databaseOperationService.storageExpansion(project, databaseId,
                idempotencyKey, request);
        return accepted(response);
    }

    @PostMapping("/{databaseId}/restart")
    @Operation(
            summary = "Restart database components",
            description = "Creates a KubeBlocks OpsRequest of type Restart. Omit componentName to restart the whole database."
    )
    public ResponseEntity<OperationResponse> restart(
            @PathVariable String project,
            @PathVariable String databaseId,
            @Parameter(description = "Unique retry-safe key", example = "restart-orders-001")
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = false,
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = "application/json",
                            examples = @io.swagger.v3.oas.annotations.media.ExampleObject(
                                    value = "{\"componentName\":\"postgresql\"}")))
            @Valid @RequestBody(required = false) RestartRequest request
    ) {
        OperationResponse response = databaseOperationService.restart(project, databaseId,
                idempotencyKey, request == null ? new RestartRequest(null) : request);
        return accepted(response);
    }

    @GetMapping("/{databaseId}/connection")
    @Operation(
            summary = "Get database connection details",
            description = "Returns managed credentials and connection URIs."
    )
    public ResponseEntity<ConnectionResponse> connection(
            @PathVariable String project,
            @PathVariable String databaseId,
            HttpServletRequest httpRequest
    ) {
        ConnectionResponse response = databaseService.connection(
                project,
                databaseId,
                clientIpResolver.resolve(httpRequest)
        );

        return ResponseEntity.ok()
                .header(
                        "Cache-Control",
                        "no-store, no-cache, must-revalidate"
                )
                .header("Pragma", "no-cache")
                .body(response);
    }

    @PostMapping("/{databaseId}/credentials/rotate")
    @Operation(summary = "Rotate managed database credentials")
    public ResponseEntity<OperationResponse> rotateCredentials(
            @PathVariable String project,
            @PathVariable String databaseId
    ) {
        return accepted(databaseService.rotateCredentials(project, databaseId));
    }

    @PutMapping("/{databaseId}/deletion-protection")
    @Operation(summary = "Enable or disable deletion protection")
    public DatabaseResponse setDeletionProtection(
            @PathVariable String project,
            @PathVariable String databaseId,
            @RequestParam boolean enabled
    ) {
        return databaseService.setDeletionProtection(
                project,
                databaseId,
                enabled
        );
    }

    @DeleteMapping("/{databaseId}")
    @Operation(
            summary = "Delete a database",
            description = "Deletion is rejected while deletion protection is enabled."
    )
    public ResponseEntity<OperationResponse> delete(
            @PathVariable String project,
            @PathVariable String databaseId
    ) {
        return accepted(databaseService.delete(project, databaseId));
    }

    private ResponseEntity<OperationResponse> accepted(OperationResponse response) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header("Location", response.statusUrl())
                .header("Operation-Location", response.statusUrl())
                .header("Retry-After", String.valueOf(response.suggestedPollingIntervalSeconds()))
                .body(response);
    }
}
