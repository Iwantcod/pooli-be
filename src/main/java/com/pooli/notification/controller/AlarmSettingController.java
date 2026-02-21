package com.pooli.notification.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pooli.notification.domain.dto.request.AlarmSettingReqDto;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "notification-setting", description = "알람 설정 관련 API")
@RestController
@RequestMapping("/api/notifications")
public class AlarmSettingController {

	@Operation(
		summary = "가족 데이터 알림 설정 변경",
	    description = "가족 데이터 알림 설정의 ON/OFF를 변경한다."
	)
	@ApiResponses({
        @ApiResponse(responseCode = "200", description = "조회 성공"),
        @ApiResponse(responseCode = "404", description = "알람 정보가 존재하지 않음"),
        @ApiResponse(responseCode = "500", description = "서버 오류"),   
	})
	@PatchMapping("/family-alarm")
	public ResponseEntity<AlarmSettingReqDto> updateFamilyAlarm(){
	 
	// @AuthenticationPrincipal LoginUser loginUser
	
	AlarmSettingReqDto response = AlarmSettingReqDto.builder()
	            .familyAlarm(false)
	            .build();

	    return ResponseEntity.ok(response);
	}
	
	@Operation(
		summary = "사용자 데이터 알림 설정 변경",
	    description = "사용자 데이터 알림 설정의 ON/OFF를 변경한다."
	)
	@ApiResponses({
        @ApiResponse(responseCode = "200", description = "조회 성공"),
        @ApiResponse(responseCode = "404", description = "알람 정보가 존재하지 않음"),
        @ApiResponse(responseCode = "500", description = "서버 오류"),   
	})
	@PatchMapping("/user-alarm")
	public ResponseEntity<AlarmSettingReqDto> updateUserAlarm(){
	 
	// @AuthenticationPrincipal LoginUser loginUser
	
	AlarmSettingReqDto response = AlarmSettingReqDto.builder()
	            .userAlarm(false)
	            .build();

	    return ResponseEntity.ok(response);
	}
	
	@Operation(
		summary = "정책 변경 알림 설정 변경",
	    description = "정책 변경 알림 설정의 ON/OFF를 변경한다."
	)
	@ApiResponses({
        @ApiResponse(responseCode = "200", description = "조회 성공"),
        @ApiResponse(responseCode = "404", description = "알람 정보가 존재하지 않음"),
        @ApiResponse(responseCode = "500", description = "서버 오류"),   
	})
	@PatchMapping("/policy-change-alarm")
	public ResponseEntity<AlarmSettingReqDto> updatepolicyChangeAlarm(){
	 
	// @AuthenticationPrincipal LoginUser loginUser
	
	AlarmSettingReqDto response = AlarmSettingReqDto.builder()
	            .policyChangeAlarm(false)
	            .build();

	    return ResponseEntity.ok(response);
	}
	
	@Operation(
		summary = "정책 한도 알림 설정 변경",
	    description = "정책 한도 알림 설정의 ON/OFF를 변경한다."
	)
	@ApiResponses({
        @ApiResponse(responseCode = "200", description = "조회 성공"),
        @ApiResponse(responseCode = "404", description = "알람 정보가 존재하지 않음"),
        @ApiResponse(responseCode = "500", description = "서버 오류"),   
	})
	@PatchMapping("/policy-limit-alarm")
	public ResponseEntity<AlarmSettingReqDto> updatePolicyLimitAlarm(){
	 
	// @AuthenticationPrincipal LoginUser loginUser
	
	AlarmSettingReqDto response = AlarmSettingReqDto.builder()
	            .policyLimitAlarm(false)
	            .build();

	    return ResponseEntity.ok(response);
	}
	
	@Operation(
		summary = "권한 변경 알림 설정 변경",
	    description = "권한 변경 알림 설정의 ON/OFF를 변경한다."
	)
	@ApiResponses({
        @ApiResponse(responseCode = "200", description = "조회 성공"),
        @ApiResponse(responseCode = "404", description = "알람 정보가 존재하지 않음"),
        @ApiResponse(responseCode = "500", description = "서버 오류"),   
	})
	@PatchMapping("/permission-alarm")
	public ResponseEntity<AlarmSettingReqDto> updatePermissionAlarm(){
	 
	// @AuthenticationPrincipal LoginUser loginUser
	
	AlarmSettingReqDto response = AlarmSettingReqDto.builder()
	            .permissionAlarm(false)
	            .build();

	    return ResponseEntity.ok(response);
	}
	
	@Operation(
		summary = "문의 사항 알림 설정 변경",
	    description = "문의 사항 알림 설정의 ON/OFF를 변경한다."
	)
	@ApiResponses({
        @ApiResponse(responseCode = "200", description = "조회 성공"),
        @ApiResponse(responseCode = "404", description = "알람 정보가 존재하지 않음"),
        @ApiResponse(responseCode = "500", description = "서버 오류"),   
	})
	@PatchMapping("/question-alarm")
	public ResponseEntity<AlarmSettingReqDto> updateQuestionAlarm(){
	 
	// @AuthenticationPrincipal LoginUser loginUser
	
	AlarmSettingReqDto response = AlarmSettingReqDto.builder()
	            .questionAlarm(false)
	            .build();

	    return ResponseEntity.ok(response);
	}
}
