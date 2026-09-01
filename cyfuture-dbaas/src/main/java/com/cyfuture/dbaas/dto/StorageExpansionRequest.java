package com.cyfuture.dbaas.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "KubeBlocks volume expansion request for one database component")
public record StorageExpansionRequest(
        @Schema(description = "Component name. Required when the database has multiple components.",
                example = "postgresql")
        @Size(max = 63)
        @Pattern(regexp = "^[a-z0-9]([a-z0-9-]*[a-z0-9])?$",
                message = "must be a valid Kubernetes component name")
        String componentName,
        @Schema(description = "Volume claim template name", example = "data")
        @Size(max = 63)
        @Pattern(regexp = "^[a-z0-9]([a-z0-9-]*[a-z0-9])?$",
                message = "must be a valid Kubernetes volume name")
        String volumeName,
        @NotBlank
        @Pattern(regexp = "^[1-9][0-9]*(Mi|Gi|Ti)$",
                message = "must be a Kubernetes storage quantity such as 30Gi")
        @Schema(description = "Desired final storage size. Shrinking is rejected.", example = "30Gi")
        String newStorageSize
) {}
