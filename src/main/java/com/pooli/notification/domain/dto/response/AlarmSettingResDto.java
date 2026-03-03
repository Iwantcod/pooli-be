package com.pooli.notification.domain.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "알람 설정 조회 응답 DTO")
public class AlarmSettingResDto {

    @Schema(description = "가족 알람", example = "true")
    private Boolean familyAlarm;

    @Schema(description = "유저 알람", example = "true")
    private Boolean userAlarm;

    @Schema(description = "정책 변경 알람", example = "true")
    private Boolean policyChangeAlarm;

    @Schema(description = "정책 한도 알람", example = "true")
    private Boolean policyLimitAlarm;

    @Schema(description = "권한 알람", example = "true")
    private Boolean permissionAlarm;

    @Schema(description = "질문 알람", example = "true")
    private Boolean questionAlarm;
}