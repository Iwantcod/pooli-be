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
@NoArgsConstructor()
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "단순 메시지 응답 DTO")
public class SimpleMessageResDto {

    @Schema(description = "응답 코드", example = "PERMISSION_DELETED")
    private String code;

    @Schema(description = "응답 메시지", example = "권한이 삭제되었습니다.")
    private String message;

    @Schema(description = "응답 시각", example = "2026-02-20T12:00:00")
    private LocalDateTime timestamp;
}
