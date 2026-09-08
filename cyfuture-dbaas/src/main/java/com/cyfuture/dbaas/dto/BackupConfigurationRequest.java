package com.cyfuture.dbaas.dto;

import com.cyfuture.dbaas.model.BackupRetentionPolicy;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/** Desired full-backup configuration. KubeBlocks receives only normalized UTC values. */
public record BackupConfigurationRequest(
        @Schema(example = "cyfuture-dbaas-backuprepo")
        @Size(max = 63) String repository,
        @Schema(example = "true") Boolean autoBackupEnabled,
        @Schema(example = "7") @Min(1) @Max(3650) Integer retentionDays,
        @Schema(example = "0 2 * * *") @Size(max = 128) String cronExpression,
        @Schema(example = "UTC") @Size(max = 60) String timezone,
        @Schema(example = "RETAIN_LATEST") BackupRetentionPolicy retentionPolicy,
        @Schema(example = "false") Boolean pitrEnabled
) {}
