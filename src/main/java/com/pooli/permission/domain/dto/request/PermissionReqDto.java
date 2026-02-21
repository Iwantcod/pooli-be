package com.pooli.permission.domain.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

@Getter
@Builder
@Setter
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "권한 생성/수정 요청 DTO")
public class PermissionReqDto {

    @Schema(description = "권한 이름", example = "데이터 차단")
    private String permissionTitle;
}
