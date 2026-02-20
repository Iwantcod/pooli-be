package com.pooli.notification.domain.dto.response;

import java.time.LocalDateTime;

import com.fasterxml.jackson.databind.JsonNode;
import com.pooli.notification.domain.enums.AlarmCode;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "알림 생성 DTO")
public class NotiResponseDto {
	
	@Schema(description = "알림 ID", example = "1")
	private Long alarmHistoryId;
   
	@Schema(description = "사용자 ID", example = "1")
	private Long userId;
	
	@Schema(description = "알림 코드", example = "policy")
	private AlarmCode alarmCode; // enum
	
	@Schema( description = "알림에서 전달하는 JSON 데이터 (없을 경우 null)", nullable = true)
	private JsonNode value;
	
    @Schema(description = "알림 읽음 여부", example = "false")
	private boolean isRead;
    
    @Schema(description = "알림 생성 시간", example = "2026-02-20T14:30:00")
    private LocalDateTime createdAt;
    
}
