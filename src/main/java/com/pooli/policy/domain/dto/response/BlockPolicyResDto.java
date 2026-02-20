package com.pooli.policy.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Block policy detail for a specific line")
public record BlockPolicyResDto(
        @Schema(description = "Line identifier", example = "101")
        Long lineId,
        @Schema(description = "Adult content block status", example = "true")
        Boolean blockAdultContent,
        @Schema(description = "Roaming data block status", example = "false")
        Boolean blockRoaming,
        @Schema(description = "Paid content block status", example = "true")
        Boolean blockPaidContent
) {
    public static BlockPolicyResDto of(
            Long lineId,
            Boolean blockAdultContent,
            Boolean blockRoaming,
            Boolean blockPaidContent
    ) {
        return new BlockPolicyResDto(lineId, blockAdultContent, blockRoaming, blockPaidContent);
    }
}

