package com.pooli.notification.controller;

import java.util.List;

import com.pooli.notification.mapper.AlarmHistoryMapper;
import com.pooli.notification.service.AlarmHistoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pooli.notification.domain.dto.request.NotiSendReqDto;
import com.pooli.notification.domain.dto.response.NotiSendResDto;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "notification", description = "알람 발송 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/notifications")
public class NotiSendController {

	private final AlarmHistoryService alarmHistoryService;

	@Operation(summary = "공지 알람 전송",
			description = "관리자가 알람을 보내는 기능")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "알람 전송 성공"),
			@ApiResponse(responseCode = "403", description = """
                    권한 없음

                    - COMMON:4301: 관리자 권한이 없습니다.
                    """),
			@ApiResponse(responseCode = "400",
					description = """
					잘못된 요청
					- COMMON:4000 요청 형식 불일치
					- COMMON:4001 요청 DTO 필드 유효성 검증 실패
					- NOTI:4001 DIRECT 타입일 경우 lineId는 필수입니다.
					- NOTI:4002 DIRECT 타입일 경우 lineId는 필수입니다.
					- NOTI:4003 알림 설정 대상이 아닌 코드입니다.
					"""),
			@ApiResponse(responseCode = "404", description = """
                    정보 없음

                    - NOTI:4401: 알림을 보낼 대상이 존재하지 않습니다.
                    """),
			@ApiResponse(responseCode = "500", description = "서버 오류")
	})
	@PreAuthorize("@authz.requireAdmin(authentication)")
	@PostMapping
	public ResponseEntity<Void> sendAlarm(
			@Valid  @RequestBody NotiSendReqDto request) {
		alarmHistoryService.sendNotification(request);
		return ResponseEntity.status(HttpStatus.CREATED).build();
	}

}
