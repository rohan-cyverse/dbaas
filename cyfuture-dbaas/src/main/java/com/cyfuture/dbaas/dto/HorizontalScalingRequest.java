package com.cyfuture.dbaas.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "KubeBlocks horizontal scaling request for one database component")
public record HorizontalScalingRequest(
        @Schema(description = "Component name. Required when the database has multiple components.",
                example = "postgresql")
        @Size(max = 63)
        @Pattern(regexp = "^[a-z0-9]([a-z0-9-]*[a-z0-9])?$",
                message = "must be a valid Kubernetes component name")
        String componentName,
        @Min(1) @Max(3)
        @Schema(description = "Desired final replica count for the component", example = "3")
        int targetReplicas
) {}
