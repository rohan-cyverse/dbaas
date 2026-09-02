package com.cyfuture.dbaas.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateProjectRequest(
        @NotBlank @Size(min = 2, max = 64) String displayName,
        @Size(max = 250) String description
) {
}
