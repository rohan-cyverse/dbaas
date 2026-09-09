package com.cyfuture.dbaas.controller;

import com.cyfuture.dbaas.dto.BackupSettingsRequest;
import com.cyfuture.dbaas.dto.BackupSettingsResponse;
import com.cyfuture.dbaas.service.BackupPolicyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/databases/{databaseId}/backup-settings")
@RequiredArgsConstructor
@Tag(name = "Backup settings", description = "Scheduled backup, retention, and PITR settings")
public class BackupSettingsController {
    private final BackupPolicyService backupPolicyService;

    @PutMapping
    @Operation(summary = "Update backup settings")
    public BackupSettingsResponse update(@PathVariable String projectId,
                                         @PathVariable String databaseId,
                                         @Valid @RequestBody BackupSettingsRequest request) {
        return backupPolicyService.update(projectId, databaseId, request);
    }

    @GetMapping
    @Operation(summary = "Get backup settings")
    public BackupSettingsResponse get(@PathVariable String projectId, @PathVariable String databaseId) {
        return backupPolicyService.get(projectId, databaseId);
    }
}
