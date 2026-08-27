package com.cyfuture.dbaas.controller;

import com.cyfuture.dbaas.dto.ConnectionResponse;
import com.cyfuture.dbaas.dto.CreateDatabaseRequest;
import com.cyfuture.dbaas.dto.CreateDatabaseResponse;
import com.cyfuture.dbaas.dto.DatabaseResponse;
import com.cyfuture.dbaas.dto.DeleteDatabaseResponse;
import com.cyfuture.dbaas.dto.OperationResponse;
import com.cyfuture.dbaas.service.ClientIpResolver;
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

import java.util.List;
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
    private final OperationService operationService;
    private final ClientIpResolver clientIpResolver;

    @GetMapping("/options")
    @Operation(
            summary = "Get supported database options",
            description = "Returns engines, modes, versions and resource-size plans accepted by the create API."
    )
    public Map<?, ?> options(@PathVariable String project) {
        databaseService.validateProject(project);
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
                .header("Retry-After", "5")
                .body(response);
    }

    @GetMapping
    @Operation(summary = "List project databases")
    public List<DatabaseResponse> list(@PathVariable String project) {
        return databaseService.list(project);
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
    public List<OperationResponse> operations(
            @PathVariable String project,
            @PathVariable String databaseId
    ) {
        databaseService.get(project, databaseId);

        return operationService.listForDatabase(
                project,
                databaseId
        );
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
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(
                        databaseService.rotateCredentials(
                                project,
                                databaseId
                        )
                );
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
    public ResponseEntity<DeleteDatabaseResponse> delete(
            @PathVariable String project,
            @PathVariable String databaseId
    ) {
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(
                        databaseService.delete(
                                project,
                                databaseId
                        )
                );
    }
}
