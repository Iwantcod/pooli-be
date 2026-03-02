package com.pooli.permission.domain.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter
@Setter
@Schema(description = "구성원 권한 부여 변경 요청 DTO")
public class MemberPermissionUpsertReqDto {

    @Schema(description = "권한 ID", example = "1")
    private Integer permissionId;

    @JsonProperty("is_enable")
    @Schema(description = "권한 활성화 여부", example = "true")
    private Boolean isEnable;
}
