package com.pooli.policy.domain.dto.response;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "현재 차단 상태 응답")
public class BlockStatusResDto {

    @Schema(description = "현재 차단 중 여부", example = "true")
    private boolean isBlocked;

    @Schema(description = "차단 종료 시간 (차단 중이 아니라면 null)", example = "2026-03-10T22:00:00")
    private LocalDateTime blockEndsAt;
}
