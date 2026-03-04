package com.pooli.notification.controller;

import com.pooli.auth.service.AuthUserDetails;
import com.pooli.notification.domain.enums.AlarmCode;
import com.pooli.notification.service.AlarmHistoryService;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pooli.common.dto.PagingResDto;
import com.pooli.notification.domain.dto.response.NotiSendResDto;
import com.pooli.notification.domain.dto.response.UnreadCountsResDto;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "notification", description = "알람 관련 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/notifications")
public class NotiReadController {

	private final AlarmHistoryService alarmHistoryService;

	@Operation(
	    summary = "알림 목록 조회",
	    description = "사용자 ID 기준 등록된 알림 목록을 모두 조회한다."
	)
	@ApiResponses({
        @ApiResponse(responseCode = "200", description = "조회 성공"),
			@ApiResponse(responseCode = "400",
					description = """
					잘못된 요청
					- COMMON:4002 RequestParam 유효성 검증 실패
					 - COMMON:4003 RequestParam 타입 불일치
					 - COMMON:4004 필수 RequestParam 누락
					 - COMMON:4008 페이지 크기(size)가 올바르지 않습니다.
					 - COMMON:4007 페이지 번호가 올바르지 않습니다.
					"""),
			@ApiResponse(responseCode = "500", description = "서버 오류")
	})
	@GetMapping
	public ResponseEntity<PagingResDto<NotiSendResDto>> getNotifications(
			@AuthenticationPrincipal AuthUserDetails userDetails,

			@Parameter(description = "페이지 넘버", example = "0")
			@RequestParam(name = "pageNumber") Integer page,

			@Parameter(description = "페이지 사이즈", example = "20")
	        @RequestParam(name = "pageSize") Integer size,

			@Parameter(description = "읽었는지 여부", example = "true")
	        @RequestParam(name = "isRead", required = false) Boolean isRead,

			@Parameter(description = "알람 코드", example = "POLICY_CHANGE")
			@RequestParam(name = "code", required = false) AlarmCode code
			){

		PagingResDto<NotiSendResDto> response =
				alarmHistoryService.getNotifications(
						userDetails.getLineId(),
						page,
						size,
						isRead,
						code
				);

		return ResponseEntity.ok(response);
	}
	
	
	@Operation(
	    summary = "알림 미읽음 개수 조회",
	    description = "사용자가 읽지 않은 알림의 개수를 모두 조회한다."
	)
	@ApiResponses({
        @ApiResponse(responseCode = "200", description = "조회 성공"),
        @ApiResponse(responseCode = "500", description = "서버 오류"),   
	})
	@GetMapping("/unread-counts")
	public ResponseEntity<UnreadCountsResDto> getUnreadCounts(
			@AuthenticationPrincipal AuthUserDetails userDetails
	){
		UnreadCountsResDto response =
				alarmHistoryService.getUnreadCounts(
						userDetails.getLineId()
				);

		return ResponseEntity.ok(response);
	}
	
	@Operation(
	    summary = "전체 알림 상태 읽음으로 변경",
	    description = "사용자가 읽지 않은 알림의 상태를 모두 읽음으로 변경한다."
	)
	@ApiResponses({
        @ApiResponse(responseCode = "200", description = "성공"),
        @ApiResponse(responseCode = "405", description = "지원하지 않는 HTTP 메서드 - COMMON:4005"),
        @ApiResponse(responseCode = "500", description = "서버 오류"),
	})
	@PatchMapping("/read-all")
	public ResponseEntity<UnreadCountsResDto> allRead(
			@AuthenticationPrincipal AuthUserDetails userDetails
	) {
		UnreadCountsResDto response = alarmHistoryService.readAll(userDetails.getLineId());
		return ResponseEntity.ok(response);
	}


	@Operation(
	    summary = "단건 알림 상태 읽음으로 변경",
	    description = "alarmHistoryId에 해당하는 알림 하나를 읽음으로 변경한다."
	)
	@ApiResponses({
        @ApiResponse(responseCode = "200", description = "성공"),
        @ApiResponse(responseCode = "400", description = """
                잘못된 요청
               
                - COMMON:4003 RequestParam 타입 불일치 (alarmHistoryId에 숫자가 아닌 값)
                - COMMON:4004 필수 RequestParam 누락 (alarmHistoryId 미입력)
                """),
        @ApiResponse(responseCode = "404", description = "알람 정보가 존재하지 않음 - NOTI:4402"),
        @ApiResponse(responseCode = "405", description = "지원하지 않는 HTTP 메서드 - COMMON:4005"),
        @ApiResponse(responseCode = "500", description = "서버 오류"),
	})
	@PatchMapping
	public ResponseEntity<NotiSendResDto> oneRead(
			@AuthenticationPrincipal AuthUserDetails userDetails,
			@Parameter(description = "알림 ID", example = "1")
			@RequestParam(name = "alarmHistoryId") Long alarmHistoryId
	) {
		NotiSendResDto response = alarmHistoryService.readOne(alarmHistoryId, userDetails.getLineId());
		return ResponseEntity.ok(response);
	}
}