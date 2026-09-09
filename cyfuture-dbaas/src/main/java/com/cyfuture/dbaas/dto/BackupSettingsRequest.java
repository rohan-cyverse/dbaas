package com.cyfuture.dbaas.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/** Desired scheduled-backup and PITR settings for one database. */
public record BackupSettingsRequest(
        @Schema(example = "true") Boolean scheduled,
        @Schema(example = "7") @Min(1) @Max(3650) Integer retentionDays,
        @Schema(example = "0 2 * * *") @Size(max = 128) String schedule,
        @Schema(example = "UTC") @Size(max = 60) String timezone,
        @Schema(example = "false") Boolean pitrEnabled
) {}
