package com.cyfuture.dbaas.controller;

import com.cyfuture.dbaas.dto.BackupResponse;
import com.cyfuture.dbaas.dto.CreateBackupRequest;
import com.cyfuture.dbaas.service.BackupService;
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
@RequestMapping("/api/v1/projects/{projectId}/databases/{databaseId}/backups")
@RequiredArgsConstructor
@Tag(name = "Backups", description = "Manual full and incremental backups, plus scheduled backup history")
public class BackupController {
    private final BackupService backupService;

    @PostMapping
    @Operation(summary = "Create a manual full or incremental backup")
    public ResponseEntity<BackupResponse> create(
            @PathVariable String projectId,
            @PathVariable String databaseId,
            @Parameter(description = "Unique retry-safe key", example = "backup-orders-20260907-001")
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateBackupRequest request) {
        BackupResponse response = backupService.create(projectId, databaseId, idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header("Location", "/api/v1/projects/" + projectId + "/databases/" + databaseId
                        + "/backups/" + response.backupId())
                .body(response);
    }

    @GetMapping
    @Operation(summary = "List database backups")
    public List<BackupResponse> list(@PathVariable String projectId, @PathVariable String databaseId) {
        return backupService.list(projectId, databaseId);
    }

    @GetMapping("/{backupId}")
    @Operation(summary = "Get backup status",
            description = "Returns product-level backup status and history.")
    public BackupResponse get(@PathVariable String projectId, @PathVariable String databaseId,
                              @PathVariable String backupId) {
        return backupService.get(projectId, databaseId, backupId);
    }

    @DeleteMapping("/{backupId}")
    @Operation(summary = "Delete a backup",
            description = "Deletes the DBaaS backup and its retained backup data.")
    public ResponseEntity<BackupResponse> delete(@PathVariable String projectId,
                                                 @PathVariable String databaseId,
                                                 @PathVariable String backupId) {
        BackupResponse response = backupService.delete(projectId, databaseId, backupId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header("Location", "/api/v1/projects/" + projectId + "/databases/" + databaseId
                        + "/backups/" + backupId)
                .body(response);
    }
}
