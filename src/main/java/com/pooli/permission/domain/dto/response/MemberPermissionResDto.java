package com.pooli.permission.domain.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
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
@Schema(description = "구성원 권한 응답 DTO")
public class MemberPermissionResDto {

    @Schema(description = "가족 ID", example = "10")
    private Long familyId;

    @Schema(description = "회선 ID", example = "1001")
    private Long lineId;

    @Schema(description = "권한 ID", example = "1")
    private Integer permissionId;

    @Schema(description = "권한 이름", example = "데이터 차단")
    private String permissionTitle;

    @JsonProperty("is_enable")
    @Schema(description = "권한 활성화 여부", example = "true")
    private Boolean isEnable;

    @Schema(description = "생성 시각", example = "2026-02-20T12:00:00")
    private LocalDateTime createdAt;
}
