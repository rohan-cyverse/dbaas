package com.cyfuture.dbaas.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "KubeBlocks restart request")
public record RestartRequest(
        @Schema(description = "Component name. Omit to restart every component in the database.",
                example = "postgresql")
        @Size(max = 63)
        @Pattern(regexp = "^[a-z0-9]([a-z0-9-]*[a-z0-9])?$",
                message = "must be a valid Kubernetes component name")
        String componentName
) {}
