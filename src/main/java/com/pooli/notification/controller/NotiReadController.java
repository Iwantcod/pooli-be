package com.pooli.notification.controller;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pooli.common.dto.PagingResDto;
import com.pooli.notification.domain.dto.response.NotiSendResDto;
import com.pooli.notification.domain.dto.response.UnreadCountsResDto;
import com.pooli.notification.domain.enums.AlarmCode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "notification", description = "알람 관련 API")
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
	public ResponseEntity<PagingResDto<NotiSendResDto>> getNotifications(    
			@RequestParam(name = "pageNumber") Integer page,
	        @RequestParam(name = "pageSize") Integer size,
	        @RequestParam(name = "isRead", required = false) Boolean isRead){
		
	    NotiSendResDto noti = NotiSendResDto.builder()
        .alarmHistoryId(1L)
        .lineId(2L)
        .alarmCode(AlarmCode.LIMIT)
        .value(null)  
        .isRead(isRead != null ? isRead : false)
        .build();
		
		// PagingResDto 빌더로 생성
		PagingResDto<NotiSendResDto> response = PagingResDto.<NotiSendResDto>builder()
		        .page(page)
		        .page(size)
		        .totalElements(1L)
		        .totalPages(1)    
		        .content(List.of(noti)) 
		        .build();

		return ResponseEntity.ok(response);
	}
	
	
	@Operation(
	    summary = "알림 미읽음 개수 조회",
	    description = "사용자가 읽지 않은 알림의 개수를 모두 조회한다."
	)
	@ApiResponses({
        @ApiResponse(responseCode = "200", description = "조회 성공"),
        @ApiResponse(responseCode = "404", description = "알람 정보가 존재하지 않음"),
        @ApiResponse(responseCode = "500", description = "서버 오류"),   
	})
	@GetMapping("/unread-counts")
	public ResponseEntity<UnreadCountsResDto> getUnreadCounts(){
		
		return ResponseEntity.ok(
				UnreadCountsResDto.builder().build()
			);
	}
	
	@Operation(
	    summary = "전체 알림 상태 읽음으로 변경",
	    description = "사용자가 읽지 않은 알림의 상태를 모두 읽음으로 변경한다."
	)
	@ApiResponses({
        @ApiResponse(responseCode = "200", description = "조회 성공"),
        @ApiResponse(responseCode = "404", description = "알람 정보가 존재하지 않음"),
        @ApiResponse(responseCode = "500", description = "서버 오류"),   
	})
	@PatchMapping("/read-all")
	public ResponseEntity<UnreadCountsResDto> allRead(){
	Long lineId = 2L; // 일단 임의로 회선 id 변경함
	UnreadCountsResDto response = UnreadCountsResDto.builder()
	            .lineId(lineId)
	            .unreadCount(0L)
	            .build();

	    return ResponseEntity.ok(response);
	}
	
	
	@Operation(
	    summary = "단건 알림의 상태 읽음으로 변경",
	    description = "사용자가 읽지 않은 알림의 상태를 모두 읽음으로 변경한다."
	)
	@ApiResponses({
        @ApiResponse(responseCode = "200", description = "조회 성공"),
        @ApiResponse(responseCode = "404", description = "알람 정보가 존재하지 않음"),
        @ApiResponse(responseCode = "500", description = "서버 오류"),   
	})
	@PatchMapping()
	public ResponseEntity<NotiSendResDto> oneRead(@RequestParam(name="alarmHistoryId") Long alarmHistoryId){
	Long lineId = 2L; // 일단 임의로 회선 id, 읽음 상태 변경함
	NotiSendResDto response = NotiSendResDto.builder()
	            .alarmHistoryId(alarmHistoryId)   
	            .lineId(lineId)
	            .alarmCode(AlarmCode.PERMISSION)     
	            .value(null)
	            .isRead(true)                      
	            .createdAt(LocalDateTime.now())
	            .build();

	    return ResponseEntity.ok(response);
	}
}