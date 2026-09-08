package com.cyfuture.dbaas.controller;

import com.cyfuture.dbaas.dto.AcceptedOperationResponse;
import com.cyfuture.dbaas.dto.BackupConfigurationRequest;
import com.cyfuture.dbaas.dto.BackupPolicyResponse;
import com.cyfuture.dbaas.service.BackupPolicyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Desired backup-policy endpoint. KubeBlocks creates and owns its generated policy and schedule CRs. */
@RestController
@RequestMapping("/api/v1/projects/{project}/databases/{databaseId}/backup-policy")
@RequiredArgsConstructor
@Tag(name = "Backup policy", description = "Scheduled full-backup configuration for KubeBlocks")
public class BackupPolicyController {
    private final BackupPolicyService backupPolicyService;

    @PutMapping
    @Operation(summary = "Update scheduled backup configuration",
            description = "Patches only Cluster.spec.backup. PITR and incremental backups return FEATURE_NOT_AVAILABLE.")
    public ResponseEntity<AcceptedOperationResponse> update(@PathVariable String project,
                                                              @PathVariable String databaseId,
                                                              @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                              @Valid @RequestBody BackupConfigurationRequest request) {
        AcceptedOperationResponse response = backupPolicyService.update(project, databaseId, idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header("Location", response.statusUrl())
                .header("Operation-Location", response.statusUrl())
                .header("Retry-After", String.valueOf(response.pollAfterSeconds()))
                .body(response);
    }

    @GetMapping
    @Operation(summary = "Get effective backup policy")
    public BackupPolicyResponse get(@PathVariable String project, @PathVariable String databaseId) {
        return backupPolicyService.get(project, databaseId);
    }
}
