package com.pooli.permission.domain.dto.response;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "구성원 권한 목록 응답 DTO")
public class MemberPermissionListResDto {

    @ArraySchema(schema = @Schema(implementation = MemberPermissionResDto.class))
    private List<MemberPermissionResDto> memberPermissions;
}
