package com.cyfuture.dbaas.dto;

import com.cyfuture.dbaas.model.RestoreMode;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record CreateRestoreRequest(
        @Pattern(regexp = "^[a-z0-9]([-a-z0-9]{0,30}[a-z0-9])?$",
                message = "name must be a DNS-compatible database name")
        String name,
        RestoreMode restoreMode,
        @Size(max = 32) String backupId,
        String restoreTime
) {
    /** Keeps Java callers of the original backup-specific restore API source-compatible. */
    public CreateRestoreRequest(String name, Instant restoreTime) {
        this(name, null, null, restoreTime == null ? null : restoreTime.toString());
    }
}
