package com.cyfuture.dbaas.dto;

import com.cyfuture.dbaas.model.BackupType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Request a manual full or incremental backup. */
public record CreateBackupRequest(
        @NotNull(message = "type is required")
        @Schema(example = "FULL", allowableValues = {"FULL", "INCREMENTAL"})
        BackupType type,
        @Min(value = 1, message = "retentionDays must be at least one day")
        @Max(value = 3650, message = "retentionDays must not exceed 3650 days")
        Integer retentionDays
) {
    /** Convenience constructor for callers that do not need a retention override. */
    public CreateBackupRequest(BackupType type) {
        this(type, null);
    }
}
