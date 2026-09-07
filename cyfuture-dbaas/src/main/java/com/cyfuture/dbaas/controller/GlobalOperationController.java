package com.cyfuture.dbaas.controller;

import com.cyfuture.dbaas.dto.OperationResponse;
import com.cyfuture.dbaas.service.OperationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operations")
@RequiredArgsConstructor
@Tag(name = "Operations", description = "Asynchronous DBaaS operation status")
public class GlobalOperationController {
    private final OperationService operationService;

    @GetMapping("/{operationId}")
    @Operation(summary = "Get operation status by immutable operation ID")
    public OperationResponse get(@PathVariable String operationId) {
        return operationService.get(operationId);
    }
}
