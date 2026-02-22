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
@Schema(description = "권한 응답 DTO")
public class PermissionResDto {

    @Schema(description = "권한 ID", example = "1")
    private Integer permissionId;

    @Schema(description = "권한 이름", example = "데이터 차단")
    private String permissionTitle;

    @Schema(description = "생성 시각", example = "2026-02-20T12:00:00")
    private LocalDateTime createdAt;

    @Schema(description = "삭제 시각", example = "2026-02-21T09:00:00", nullable = true)
    private LocalDateTime deletedAt;
}
