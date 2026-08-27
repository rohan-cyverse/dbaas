package com.cyfuture.dbaas.controller;

import com.cyfuture.dbaas.dto.OperationResponse;
import com.cyfuture.dbaas.service.DatabaseService;
import com.cyfuture.dbaas.service.OperationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{project}/databases/{databaseId}/operations")
@RequiredArgsConstructor
@Tag(name = "Operations", description = "Asynchronous provisioning-operation status")
public class OperationController {
    private final OperationService operationService;
    private final DatabaseService databaseService;

    @GetMapping("/{operationId}")
    @Operation(summary = "Get operation status",
            description = "Poll until the operation becomes SUCCEEDED or FAILED.")
    public OperationResponse get(@PathVariable String project,
                                 @PathVariable String databaseId,
                                 @PathVariable String operationId) {
        databaseService.get(project, databaseId);
        return operationService.getForDatabase(project, databaseId, operationId);
    }
}
