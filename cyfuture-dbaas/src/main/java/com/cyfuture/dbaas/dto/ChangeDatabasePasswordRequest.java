package com.cyfuture.dbaas.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ChangeDatabasePasswordRequest(
        @Schema(
                example = "N3w_Secure-Pass-2026",
                accessMode = Schema.AccessMode.WRITE_ONLY,
                description = "New password for the managed database user"
        )
        @NotBlank
        @Size(min = 8, max = 128)
        @Pattern(regexp = "^[A-Za-z0-9_@#%+=:,.?-]+$",
                message = "must be 8-128 characters using letters, numbers, and _ @ # % + = : , . ? -")
        String password
) {}
