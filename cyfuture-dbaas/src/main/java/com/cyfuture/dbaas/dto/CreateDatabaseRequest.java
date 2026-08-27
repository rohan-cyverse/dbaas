package com.cyfuture.dbaas.dto;

import com.cyfuture.dbaas.model.DatabaseEngine;
import com.cyfuture.dbaas.model.DatabaseMode;
import com.cyfuture.dbaas.model.SizePlan;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

public record CreateDatabaseRequest(
        @Schema(example = "orders-postgres")
        @NotBlank @Size(min = 4, max = 32)
        @Pattern(regexp = "^[a-z][a-z0-9-]*[a-z0-9]$",
                message = "must contain lowercase letters, numbers and hyphens, and start with a letter")
        String name,
        @Schema(example = "Orders development database") @Size(max = 64) String remark,
        @Schema(example = "POSTGRESQL") @NotNull DatabaseEngine engine,
        @Schema(example = "STANDALONE") @NotNull DatabaseMode mode,
        @Schema(example = "17.5.0") @NotBlank String version,
        @Schema(example = "C1G1") @NotNull SizePlan size,
        @Schema(example = "10") @Min(10) @Max(2048) int storageGi,
        @Schema(example = "1") @Min(1) @Max(3) int replicas,
        @Schema(example = "0") @Min(0) @Max(8) int shards,
        @Schema(example = "Asia/Kolkata") @Size(max = 60) String timezone,
        @Schema(hidden = true) @Size(max = 10) List<String> allowedCidrs,
        @Schema(example = "true") boolean deletionProtection,
        @Schema(example = "{\"environment\":\"test\",\"team\":\"orders\"}")
        @Size(max = 20) Map<String, String> tags
) {
    public CreateDatabaseRequest {
        if (allowedCidrs == null) allowedCidrs = List.of();
    }
}
