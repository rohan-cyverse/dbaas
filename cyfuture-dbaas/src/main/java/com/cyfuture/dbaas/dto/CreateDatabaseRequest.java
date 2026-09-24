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
import jakarta.validation.Valid;

import java.util.List;
import java.util.Map;

public record CreateDatabaseRequest(
        @Schema(example = "orders_db", description = "Required user-defined database name. This exact name is created inside PostgreSQL, MySQL, or MongoDB and is also used as the display name. It must be unique within the project.", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(max = 32)
        @Pattern(regexp = "^[a-z][a-z0-9_]{0,31}$",
                message = "must start with a lowercase letter and contain only lowercase letters, numbers, and underscores")
        String name,
        @Schema(example = "orders_user", description = "Required user-defined database username. It must be unique within the project.", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(max = 32)
        @Pattern(regexp = "^[a-z][a-z0-9_]{0,31}$",
                message = "must start with a lowercase letter and contain only lowercase letters, numbers, and underscores")
        String username,
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
        @Size(max = 20) Map<String, String> tags,
        @Schema(
                example = "S3cure_Pass-2026",
                accessMode = Schema.AccessMode.WRITE_ONLY,
                description = "Required password for the managed database user.",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank
        @Size(min = 8, max = 128)
        @Pattern(regexp = "^[A-Za-z0-9_@#%+=:,.?-]+$",
                message = "must be 8-128 characters using letters, numbers, and _ @ # % + = : , . ? -")
        String password,
        @Schema(
                description = "Required scheduled-backup configuration. Explicitly set scheduled, retentionDays, timezone, and pitrEnabled; schedule is required when scheduled is true. PITR is available for supported database configurations.",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotNull(message = "backup configuration is required when creating a database")
        @Valid BackupSettingsRequest backup
) {
    public CreateDatabaseRequest {
        if (allowedCidrs == null) allowedCidrs = List.of();
    }

    /**
     * Keeps Java callers compiled against the original database-create payload.
     * DatabaseService rejects this legacy form because new databases require backup settings.
     */
    public CreateDatabaseRequest(String name, String remark, DatabaseEngine engine, DatabaseMode mode,
                                 String version, SizePlan size, int storageGi, int replicas, int shards,
                                 String timezone, List<String> allowedCidrs,
                                 boolean deletionProtection, Map<String, String> tags) {
        this(name, remark, engine, mode, version, size, storageGi, replicas, shards, timezone,
                allowedCidrs, deletionProtection, tags, null, null);
    }

    public CreateDatabaseRequest(String name, String remark, DatabaseEngine engine, DatabaseMode mode,
                                 String version, SizePlan size, int storageGi, int replicas, int shards,
                                 String timezone, List<String> allowedCidrs,
                                 boolean deletionProtection, Map<String, String> tags,
                                 BackupSettingsRequest backup) {
        this(name, remark, engine, mode, version, size, storageGi, replicas, shards, timezone,
                allowedCidrs, deletionProtection, tags, null, backup);
    }

    public CreateDatabaseRequest(String name, String username, String remark,
                                 DatabaseEngine engine, DatabaseMode mode,
                                 String version, SizePlan size, int storageGi, int replicas, int shards,
                                 String timezone, List<String> allowedCidrs,
                                 boolean deletionProtection, Map<String, String> tags) {
        this(name, username, remark, engine, mode, version, size, storageGi, replicas, shards,
                timezone, allowedCidrs, deletionProtection, tags, null, null);
    }

    public CreateDatabaseRequest(String name, String username, String remark,
                                 DatabaseEngine engine, DatabaseMode mode,
                                 String version, SizePlan size, int storageGi, int replicas, int shards,
                                 String timezone, List<String> allowedCidrs,
                                 boolean deletionProtection, Map<String, String> tags,
                                 BackupSettingsRequest backup) {
        this(name, username, remark, engine, mode, version, size, storageGi, replicas, shards,
                timezone, allowedCidrs, deletionProtection, tags, null, backup);
    }

    public CreateDatabaseRequest(String name, String remark, DatabaseEngine engine, DatabaseMode mode,
                                 String version, SizePlan size, int storageGi, int replicas, int shards,
                                 String timezone, List<String> allowedCidrs,
                                 boolean deletionProtection, Map<String, String> tags,
                                 String password, BackupSettingsRequest backup) {
        this(name, null, remark, engine, mode, version, size, storageGi, replicas, shards, timezone,
                allowedCidrs, deletionProtection, tags, password, backup);
    }
}
