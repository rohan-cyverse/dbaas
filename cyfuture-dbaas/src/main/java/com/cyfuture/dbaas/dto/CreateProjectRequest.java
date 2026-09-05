package com.cyfuture.dbaas.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateProjectRequest(
        @NotBlank @Size(min = 2, max = 64) String displayName,
        @Size(max = 250) String description
) {
    /** Source compatibility for older callers; name is deliberately ignored. */
    public CreateProjectRequest(String ignoredName, String displayName, String description) {
        this(displayName, description);
    }
}
