package com.cyfuture.dbaas.dto;

import com.cyfuture.dbaas.model.RestoreMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Supply the mode and only the field required by that mode. */
public record CreateRestoreRequest(
        @NotNull(message = "mode is required")
        @Schema(example = "FULL", allowableValues = {"FULL", "POINT_IN_TIME"})
        RestoreMode mode,
        @Size(max = 32) String backupId,
        @Schema(example = "2026-09-09T08:30:00Z") String restoreTime
) {}
