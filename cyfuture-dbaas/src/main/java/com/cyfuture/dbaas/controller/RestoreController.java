package com.cyfuture.dbaas.controller;

import com.cyfuture.dbaas.dto.CreateRestoreRequest;
import com.cyfuture.dbaas.dto.RestoreResponse;
import com.cyfuture.dbaas.service.RestoreService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Database-scoped full and point-in-time restores, always into a new Cluster. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/databases/{databaseId}/restores")
@RequiredArgsConstructor
@Tag(name = "Restores", description = "Full and point-in-time restores into new databases")
public class RestoreController {
    private final RestoreService restoreService;

    @PostMapping
    @Operation(summary = "Restore a full backup or recover to a point in time",
            description = "Use mode=FULL with backupId or mode=POINT_IN_TIME with restoreTime. "
                    + "A new database is always created.")
    public ResponseEntity<RestoreResponse> restore(@PathVariable String projectId,
                                                    @PathVariable String databaseId,
                                                    @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                    @Valid @RequestBody CreateRestoreRequest request) {
        RestoreResponse response = restoreService.restore(projectId, databaseId, idempotencyKey, request);
        return ResponseEntity.accepted()
                .header("Location", "/api/v1/projects/" + projectId + "/databases/" + databaseId
                        + "/restores/" + response.restoreId())
                .body(response);
    }

    @GetMapping
    @Operation(summary = "List restore history")
    public List<RestoreResponse> list(@PathVariable String projectId,
                                      @PathVariable String databaseId) {
        return restoreService.list(projectId, databaseId);
    }

    @GetMapping("/{restoreId}")
    @Operation(summary = "Get restore status")
    public RestoreResponse get(@PathVariable String projectId, @PathVariable String databaseId,
                               @PathVariable String restoreId) {
        return restoreService.get(projectId, databaseId, restoreId);
    }
}
