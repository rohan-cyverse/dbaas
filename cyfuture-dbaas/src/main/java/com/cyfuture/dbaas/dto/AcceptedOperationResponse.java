package com.cyfuture.dbaas.dto;

import com.cyfuture.dbaas.model.OperationStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/** Uniform response for every asynchronous backup lifecycle mutation. */
@Schema(description = "Accepted asynchronous backup, policy, deletion, or restore operation")
public record AcceptedOperationResponse(
        @Schema(example = "op-7d4cba9f4bd2") String operationId,
        @Schema(example = "bkp-0eb83c49ab21") String resourceId,
        @Schema(example = "PENDING") OperationStatus status,
        @Schema(example = "/api/v1/operations/op-7d4cba9f4bd2") String statusUrl,
        @Schema(example = "5") int pollAfterSeconds
) {}
