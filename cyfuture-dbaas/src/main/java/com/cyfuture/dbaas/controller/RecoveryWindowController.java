package com.cyfuture.dbaas.controller;

import com.cyfuture.dbaas.dto.RecoveryWindowResponse;
import com.cyfuture.dbaas.service.BackupPolicyService;
import com.cyfuture.dbaas.service.PitrRecoveryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Observed PITR health and safely reportable recovery bounds. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/databases/{databaseId}/recovery-window")
@RequiredArgsConstructor
@Tag(name = "Recovery window", description = "Observed point-in-time recovery coverage")
public class RecoveryWindowController {
    private final PitrRecoveryService pitrRecoveryService;
    private final BackupPolicyService backupPolicyService;

    @GetMapping
    @Operation(summary = "Get observed PITR recovery window",
            description = "A window is READY only after a completed base backup and healthy continuous log coverage are observed.")
    public RecoveryWindowResponse get(@PathVariable String projectId, @PathVariable String databaseId) {
        backupPolicyService.refresh(projectId, databaseId);
        return pitrRecoveryService.response(pitrRecoveryService.window(projectId, databaseId));
    }
}
