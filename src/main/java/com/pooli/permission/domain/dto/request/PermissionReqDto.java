package com.pooli.permission.domain.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

@Getter
@Setter
@Schema(description = "권한 생성/수정 요청 DTO")
public class PermissionReqDto {

    @NotBlank(message = "권한 이름은 비어 있을 수 없습니다.")
    @Size(max = 20, message = "권한 이름은 20자 이하여야 합니다.")
    @Schema(description = "권한 이름", example = "데이터 차단")
    private String permissionTitle;
}
