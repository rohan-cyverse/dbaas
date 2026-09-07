package com.cyfuture.dbaas.controller;

import com.cyfuture.dbaas.dto.BackupAcceptedResponse;
import com.cyfuture.dbaas.dto.BackupResponse;
import com.cyfuture.dbaas.dto.CreateBackupRequest;
import com.cyfuture.dbaas.dto.CreateRestoreRequest;
import com.cyfuture.dbaas.dto.RestoreAcceptedResponse;
import com.cyfuture.dbaas.service.BackupService;
import com.cyfuture.dbaas.service.RestoreService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/projects/{project}/databases/{databaseId}/backups")
@RequiredArgsConstructor
@Tag(name = "Backups", description = "Asynchronous KubeBlocks full backup and restore APIs")
public class BackupController {
    private final BackupService backupService;
    private final RestoreService restoreService;

    @PostMapping
    @Operation(summary = "Create a manual full backup",
            description = "Creates metadata and an operation first, then asynchronously submits a KubeBlocks Backup CR. Only FULL backups are enabled in this release.")
    public ResponseEntity<BackupAcceptedResponse> create(
            @PathVariable String project,
            @PathVariable String databaseId,
            @Parameter(description = "Unique retry-safe key", example = "backup-orders-20260907-001")
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody(required = false) CreateBackupRequest request) {
        BackupAcceptedResponse response = backupService.create(project, databaseId, idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header("Location", response.statusUrl())
                .header("Operation-Location", "/api/v1/operations/" + response.operationId())
                .header("Retry-After", String.valueOf(response.pollAfterSeconds()))
                .body(response);
    }

    @GetMapping
    @Operation(summary = "List database backups")
    public List<BackupResponse> list(@PathVariable String project, @PathVariable String databaseId) {
        return backupService.list(project, databaseId);
    }

    @GetMapping("/{backupId}")
    @Operation(summary = "Get backup status",
            description = "Never returns object-storage credentials, encryption keys, Kubernetes Secrets, or database passwords.")
    public BackupResponse get(@PathVariable String project, @PathVariable String databaseId,
                              @PathVariable String backupId) {
        return backupService.get(project, databaseId, backupId);
    }

    @DeleteMapping("/{backupId}")
    @Operation(summary = "Purge a backup",
            description = "Explicitly deletes the DBaaS-owned KubeBlocks Backup CR. Its Delete deletion policy removes the associated object-storage data. Unknown Kubernetes resources are never deleted.")
    public ResponseEntity<BackupResponse> purge(@PathVariable String project, @PathVariable String databaseId,
                                                 @PathVariable String backupId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(backupService.purge(project, databaseId, backupId));
    }

    @PostMapping("/{backupId}/restore")
    @Operation(summary = "Restore a completed backup to a new database",
            description = "Never overwrites the source database. Returns completion only after KubeBlocks, managed credentials, and the shared public gateway route are ready.")
    public ResponseEntity<RestoreAcceptedResponse> restore(
            @PathVariable String project,
            @PathVariable String databaseId,
            @PathVariable String backupId,
            @Parameter(description = "Unique retry-safe key", example = "restore-orders-20260907-001")
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody(required = false) CreateRestoreRequest request) {
        RestoreAcceptedResponse response = restoreService.restore(project, databaseId, backupId,
                idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header("Location", response.statusUrl())
                .header("Operation-Location", response.statusUrl())
                .header("Retry-After", String.valueOf(response.pollAfterSeconds()))
                .body(response);
    }
}
