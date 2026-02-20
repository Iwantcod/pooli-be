package com.pooli.permission.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "공통 에러 응답 DTO")
public class CommonErrorResDto {

    @Schema(description = "에러 코드", example = "PERMISSION_NOT_FOUND")
    private String code;

    @Schema(description = "에러 메시지", example = "권한 정보를 찾을 수 없습니다.")
    private String message;

    @Schema(description = "에러 발생 시각", example = "2026-02-20T12:00:00")
    private LocalDateTime timestamp;
}
//공통에러응답 만들면 삭제