package com.cyfuture.dbaas.controller;

import com.cyfuture.dbaas.service.DatabaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/databases")
@RequiredArgsConstructor
@Tag(name = "Database Options", description = "Global database engine, mode, version and size options")
public class DatabaseOptionsController {
    private final DatabaseService databaseService;

    @GetMapping("/options")
    @Operation(
            summary = "Get supported database options",
            description = "Returns globally supported engines, modes, versions and resource-size plans accepted by the create API."
    )
    public Map<?, ?> options() {
        return databaseService.options();
    }
}
