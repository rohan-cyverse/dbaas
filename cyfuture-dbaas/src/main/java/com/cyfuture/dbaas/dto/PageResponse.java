package com.cyfuture.dbaas.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** Stable lightweight pagination envelope shared by catalog and history APIs. */
@Schema(description = "Stable paginated catalog response")
public record PageResponse<T>(
        @Schema(description = "Results for this page") List<T> items,
        @Schema(example = "0") int page,
        @Schema(example = "20") int size,
        @Schema(example = "42", description = "Total matching records across all pages") long totalItems,
        @Schema(example = "3") int totalPages
) {}
