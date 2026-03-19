package com.pooli.policy.domain.dto.response;

import java.util.List;

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
@Schema(description = "관리자 - repeat block Redis 전체 재적재 응답 DTO")
public class RepeatBlockRehydrateAllResDto {

    @Schema(description = "재적재 대상 line 수", example = "120")
    private int totalLineCount;

    @Schema(description = "재적재 성공 line 수", example = "118")
    private int successCount;

    @Schema(description = "재적재 실패 line 수", example = "2")
    private int failureCount;

    @Schema(description = "재적재 실패 line 목록", example = "[104, 208]")
    private List<Long> failedLineIds;
}
