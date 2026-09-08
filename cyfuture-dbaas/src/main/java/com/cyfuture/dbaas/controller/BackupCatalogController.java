package com.cyfuture.dbaas.controller;

import com.cyfuture.dbaas.dto.BackupRepositoryResponse;
import com.cyfuture.dbaas.dto.BackupResponse;
import com.cyfuture.dbaas.dto.PageResponse;
import com.cyfuture.dbaas.dto.RestoreHistoryResponse;
import com.cyfuture.dbaas.model.BackupStatus;
import com.cyfuture.dbaas.model.BackupTriggerMethod;
import com.cyfuture.dbaas.model.BackupType;
import com.cyfuture.dbaas.model.DatabaseEngine;
import com.cyfuture.dbaas.model.RestoreStatus;
import com.cyfuture.dbaas.service.BackupCatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** Global project-owned catalog and immutable audit APIs. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Backup catalog", description = "Available backup sets and immutable backup/restore history")
public class BackupCatalogController {
    private final BackupCatalogService catalogService;

    @GetMapping("/backups")
    @Operation(summary = "List available Backup Set entries")
    public PageResponse<BackupResponse> backups(
            @RequestParam(required = false) String project,
            @RequestParam(required = false) String databaseId,
            @RequestParam(required = false) DatabaseEngine engine,
            @RequestParam(required = false) BackupStatus status,
            @RequestParam(required = false) BackupTriggerMethod triggerMethod,
            @RequestParam(required = false) BackupType backupType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort, @RequestParam(required = false) String order) {
        return catalogService.backupSet(new BackupCatalogService.BackupQuery(project, databaseId, engine, status,
                triggerMethod, backupType, from, to, search, page, size, sort, order));
    }

    @GetMapping("/backup-history")
    @Operation(summary = "List immutable backup history")
    public PageResponse<BackupResponse> backupHistory(
            @RequestParam(required = false) String project,
            @RequestParam(required = false) String databaseId,
            @RequestParam(required = false) DatabaseEngine engine,
            @RequestParam(required = false) BackupStatus status,
            @RequestParam(required = false) BackupTriggerMethod triggerMethod,
            @RequestParam(required = false) BackupType backupType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort, @RequestParam(required = false) String order) {
        return catalogService.backupHistory(new BackupCatalogService.BackupQuery(project, databaseId, engine, status,
                triggerMethod, backupType, from, to, search, page, size, sort, order));
    }

    @GetMapping("/restore-history")
    @Operation(summary = "List immutable restore history")
    public PageResponse<RestoreHistoryResponse> restoreHistory(
            @RequestParam(required = false) String project,
            @RequestParam(required = false) String databaseId,
            @RequestParam(required = false) DatabaseEngine engine,
            @RequestParam(required = false) RestoreStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort, @RequestParam(required = false) String order) {
        return catalogService.restoreHistory(new BackupCatalogService.RestoreQuery(project, databaseId, engine, status,
                from, to, search, page, size, sort, order));
    }

    @GetMapping("/backup-repositories")
    @Operation(summary = "List safe BackupRepo state")
    public List<BackupRepositoryResponse> repositories() {
        return catalogService.repositories();
    }
}
