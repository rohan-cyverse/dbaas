package com.cyfuture.dbaas.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "KubeBlocks restart request")
public record RestartRequest(
        @Schema(description = "Component name. Omit to restart every component in the database.",
                example = "postgresql")
        String componentName
) {}
