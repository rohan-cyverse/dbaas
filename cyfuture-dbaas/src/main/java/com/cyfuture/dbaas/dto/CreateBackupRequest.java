package com.cyfuture.dbaas.dto;

import com.cyfuture.dbaas.model.BackupType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

/** Only FULL is accepted today; the stable fields reserve future API evolution. */
public record CreateBackupRequest(
        BackupType type,
        @Min(value = 1, message = "retentionDays must be at least one day")
        @Max(value = 3650, message = "retentionDays must not exceed 3650 days")
        Integer retentionDays,
        @Pattern(regexp = "^(?:[1-9][0-9]*(?:y|mo|d|h|m))+$",
                message = "retention must use values such as 7d or 1mo7d")
        String retention
) {
    /** Compatibility constructor for callers using the original type/retention contract. */
    public CreateBackupRequest(BackupType type, String retention) {
        this(type, null, retention);
    }
}
