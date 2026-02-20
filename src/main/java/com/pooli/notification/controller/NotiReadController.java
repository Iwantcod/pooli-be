package com.pooli.notification.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pooli.common.dto.PagingResDto;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "notification", description = "알람 목록 조회 API")
@RestController
@RequestMapping("/api/notifications")
public class NotiReadController {

	@Operation(
	    summary = "알림 목록 조회",
	    description = "사용자 ID 기준 등록된 알림 목록을 모두 조회한다."
	)
	@ApiResponses({
        @ApiResponse(responseCode = "200", description = "조회 성공"),
        @ApiResponse(responseCode = "404", description = "알람 정보가 존재하지 않음"),
        @ApiResponse(responseCode = "500", description = "서버 오류"),   
	})
	@GetMapping
	public ResponseEntity<PagingResDto> getNotifications(    
			@RequestParam(name = "pageNumber") Integer page,
	        @RequestParam(name = "pageSize") Integer size,
	        @RequestParam(name = "isRead", required = false) Boolean isRead){
		return ResponseEntity.ok(new PagingResDto());
	}
}