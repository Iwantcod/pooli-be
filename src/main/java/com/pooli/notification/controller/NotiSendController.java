package com.pooli.notification.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
@RequestMapping("/api/notifications")
public class NotiSendController {

	@Operation(
	    summary = "알림 발송 요청",
	    description = "새로운 알림 발송 요청을 생성한다"
	)
	@ApiResponses({
	    @ApiResponse(responseCode = "201", description = "알림 생성 성공"),
	    @ApiResponse(responseCode = "400", description = "잘못된 요청"),
	    @ApiResponse(responseCode = "500", description = "서버 오류"),
	        
	})
	@PostMapping
	public ResponseEntity<List<NotiSendResDto>> sendAlarm(@RequestBody NotiSendReqDto request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(List.of());
	}

}
