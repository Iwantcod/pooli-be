package com.pooli.permission.domain.dto.response;

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
@Schema(description = "가족관리자 역할 양도 응답 DTO")
public class RepresentativeRoleTransferResDto {

    @Schema(description = "기존 대표 회선 ID", example = "1001")
    private Long currentOwnerLineId;

    @Schema(description = "새 대표 회선 ID", example = "1002")
    private Long changeOwnerLineId;
}
