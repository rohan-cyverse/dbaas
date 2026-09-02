package com.cyfuture.dbaas.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateProjectRequest(
        @NotBlank
        @Pattern(regexp = "^[a-z][a-z0-9-]{1,28}[a-z0-9]$",
                message = "must be 3-30 lowercase letters, numbers or hyphens")
        String name,
        @NotBlank @Size(min = 2, max = 64) String displayName,
        @Size(max = 250) String description
) {
}
