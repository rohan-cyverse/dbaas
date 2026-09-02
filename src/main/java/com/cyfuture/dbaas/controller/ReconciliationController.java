package com.cyfuture.dbaas.controller;

import com.cyfuture.dbaas.dto.OrphanedDatabaseResponse;
import com.cyfuture.dbaas.service.ReconciliationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/reconciliation")
@RequiredArgsConstructor
@Tag(name = "Reconciliation", description = "Metadata and Kubernetes synchronization inspection")
public class ReconciliationController {
    private final ReconciliationService reconciliationService;

    @GetMapping("/orphans")
    @Operation(summary = "List DBaaS-managed KubeBlocks clusters absent from metadata")
    public List<OrphanedDatabaseResponse> orphans() {
        return reconciliationService.orphans();
    }
}
