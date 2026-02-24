package com.pooli.notification.domain.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "알림 설정 변경 요청 DTO")
public class AlarmSettingReqDto {

	@Schema(description = "가족 데이터 알림 설정 변경", example = "true")
	Boolean familyAlarm;
	
	@Schema(description = "사용자 데이터 알림 설정 변경", example = "true")
	Boolean userAlarm;
	
	@Schema(description = "정책 변경 알림 설정 변경", example = "true")
	Boolean policyChangeAlarm;
	
	@Schema(description = "정책 한도 알림 설정 변경", example = "true")
	Boolean policyLimitAlarm;
	
	@Schema(description = "권한 변경 알림 설정 변경", example = "true")
	Boolean permissionAlarm;
	
	@Schema(description = "문의 사항 알림 설정 변경", example = "true")
	Boolean questionAlarm;
	
}
