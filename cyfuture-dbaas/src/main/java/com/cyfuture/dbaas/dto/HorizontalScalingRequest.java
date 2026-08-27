package com.cyfuture.dbaas.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@Schema(description = "KubeBlocks horizontal scaling request for one database component")
public record HorizontalScalingRequest(
        @Schema(description = "Component name. Required when the database has multiple components.",
                example = "postgresql")
        String componentName,
        @Min(1) @Max(99)
        @Schema(description = "Desired final replica count for the component", example = "3")
        int targetReplicas
) {}
