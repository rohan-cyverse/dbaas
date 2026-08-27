package com.cyfuture.dbaas.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@Schema(description = "KubeBlocks vertical scaling request for one database component")
public record VerticalScalingRequest(
        @Schema(description = "Component name. Required when the database has multiple components.",
                example = "postgresql")
        String componentName,
        @Valid @NotNull
        ResourceValues requests,
        @Valid @NotNull
        ResourceValues limits
) {
    public record ResourceValues(
            @NotBlank
            @Pattern(regexp = "^([0-9]+m|[0-9]+(\\.[0-9]+)?)$",
                    message = "must be a Kubernetes CPU quantity such as 500m, 1 or 1.5")
            @Schema(example = "1")
            String cpu,
            @NotBlank
            @Pattern(regexp = "^[0-9]+(Mi|Gi)$",
                    message = "must be a Kubernetes memory quantity such as 512Mi or 2Gi")
            @Schema(example = "2Gi")
            String memory
    ) {}
}
