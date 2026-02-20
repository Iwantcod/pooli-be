package com.pooli.common.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Common error response")
public record ErrorResponse(
        @Schema(description = "Application error code", example = "POLICY_NOT_FOUND")
        String code,
        @Schema(description = "Error message", example = "Requested policy does not exist.")
        String message,
        @Schema(description = "Error timestamp", example = "2026-02-19T12:00:00")
        String timestamp,
        @Schema(description = "Request path", example = "/api/lines/policies")
        String path
) {
}
