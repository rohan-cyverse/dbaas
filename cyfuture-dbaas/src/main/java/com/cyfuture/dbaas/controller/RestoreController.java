package com.cyfuture.dbaas.controller;

import com.cyfuture.dbaas.dto.AcceptedOperationResponse;
import com.cyfuture.dbaas.dto.CreateRestoreRequest;
import com.cyfuture.dbaas.service.RestoreService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Database-scoped full and point-in-time restores, always into a new Cluster. */
@RestController
@RequestMapping("/api/v1/projects/{project}/databases/{databaseId}/restores")
@RequiredArgsConstructor
@Tag(name = "Restores", description = "Asynchronous full and point-in-time KubeBlocks restores")
public class RestoreController {
    private final RestoreService restoreService;

    @PostMapping
    @Operation(summary = "Restore a completed full backup or recover to a point in time",
            description = "The source database is never overwritten. Successful completion requires KubeBlocks, managed credentials, and the public route to be ready.")
    public ResponseEntity<AcceptedOperationResponse> restore(@PathVariable String project,
                                                              @PathVariable String databaseId,
                                                              @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                              @Valid @RequestBody CreateRestoreRequest request) {
        AcceptedOperationResponse response = restoreService.restore(project, databaseId, idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header("Location", response.statusUrl())
                .header("Operation-Location", response.statusUrl())
                .header("Retry-After", String.valueOf(response.pollAfterSeconds()))
                .body(response);
    }
}
