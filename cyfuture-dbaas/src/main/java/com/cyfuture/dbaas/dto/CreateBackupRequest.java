package com.cyfuture.dbaas.dto;

import com.cyfuture.dbaas.model.BackupType;
import jakarta.validation.constraints.Pattern;

/** Only FULL is accepted today; the stable fields reserve future API evolution. */
public record CreateBackupRequest(
        BackupType type,
        @Pattern(regexp = "^(?:[1-9][0-9]*(?:y|mo|d|h|m))+$",
                message = "retention must use values such as 7d or 1mo7d")
        String retention
) {}
