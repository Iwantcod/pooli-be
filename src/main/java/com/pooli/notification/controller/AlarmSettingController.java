package com.pooli.notification.controller;

import com.pooli.auth.service.AuthUserDetails;
import com.pooli.notification.domain.dto.response.AlarmSettingResDto;
import com.pooli.notification.domain.enums.AlarmType;
import com.pooli.notification.service.AlarmSettingService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import com.pooli.notification.domain.dto.request.AlarmSettingReqDto;

import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "notification-setting", description = "알람 설정 관련 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/notifications/settings")
public class AlarmSettingController {

	final private AlarmSettingService alarmSettingService;

	@Operation(
			summary = "알람 설정 변경",
			description = "사용자의 알람 설정을 변경합니다."
	)
	@ApiResponses({
			@ApiResponse(responseCode = "204", description = "알람 설정 변경 성공"),
			@ApiResponse(responseCode = "400",
					description = """
                잘못된 요청
                - COMMON:4000 요청 형식 불일치
                - COMMON:4001 요청 DTO 필드 유효성 검증 실패
                - COMMON:4002 RequestParam 유효성 검증 실패
				- COMMON:4003 RequestParam 타입 불일치
				- COMMON:4004 필수 RequestParam 누락
                """),
			@ApiResponse(responseCode = "500",
					description = """
                서버 오류
                - COMMON:5000 서버 내부 오류 발생
                - COMMON:5001 데이터베이스 오류
                """)
	})
	@PatchMapping
	public ResponseEntity<Void> toggleAlarm(
			@AuthenticationPrincipal AuthUserDetails userDetails,
			@RequestParam AlarmType type,
			@Valid @RequestBody AlarmSettingReqDto request
	) {
		alarmSettingService.updateAlarmSetting(
				userDetails.getUserId(),
				type,
				request.getEnabled()
		);

		return ResponseEntity.noContent().build();
	}


	@Operation(
			summary = "알람 설정 조회",
			description = "사용자의 알람 설정을 조회합니다."
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "알람 설정 조회 성공"),
			@ApiResponse(responseCode = "500",
					description = """
                서버 오류
                - COMMON:5000 서버 내부 오류 발생
                - COMMON:5001 데이터베이스 오류
                """)
	})
	@GetMapping
	public ResponseEntity<AlarmSettingResDto> getAlarmSetting(
			@AuthenticationPrincipal AuthUserDetails userDetails
	) {
		AlarmSettingResDto response =
				alarmSettingService.getAlarmSetting(userDetails.getUserId());

		return ResponseEntity.ok(response);
	}


}
